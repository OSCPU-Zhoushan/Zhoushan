package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._

class Lsu extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val ecp = Output(new ExCommitPacket)
    val busy = Output(Bool())
    val dmem_st = new CacheBusIO
    val dmem_ld = new CacheBusIO
    val flush = Input(Bool())
  })

  val lsu_update = io.uop.valid

  val uop = HoldUnlessWithFlush(io.uop, lsu_update, io.flush)
  val in1 = HoldUnlessWithFlush(io.in1, lsu_update, io.flush)
  val in2 = HoldUnlessWithFlush(io.in2, lsu_update, io.flush)

  val completed = RegInit(true.B)
  when (lsu_update) {
    completed := false.B
  }

  val is_mem = (uop.fu_code === s"b$FU_MEM".U)
  val is_load = (uop.mem_code === s"b$MEM_LD".U || uop.mem_code === s"b$MEM_LDU".U)
  val is_store = (uop.mem_code === s"b$MEM_ST".U)

  val s_idle :: s_wait_r :: s_wait_w :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val st_req = io.dmem_st.req
  val st_resp = io.dmem_st.resp
  val ld_req = io.dmem_ld.req
  val ld_resp = io.dmem_ld.resp

  val addr = (in1 + SignExt32_64(uop.imm))(31, 0)
  val addr_offset = addr(2, 0)
  val wdata = in2

  val mmio = (addr(31) === 0.U)

  val mask = ("b11111111".U << addr_offset)(7, 0)
  val wmask = MuxLookup(uop.mem_size, 0.U, Array(
    s"b$MEM_BYTE".U  -> "b00000001".U(8.W),
    s"b$MEM_HALF".U  -> "b00000011".U(8.W),
    s"b$MEM_WORD".U  -> "b00001111".U(8.W),
    s"b$MEM_DWORD".U -> "b11111111".U(8.W)
  ))

  // memory address unaligned
  //    half  -> offset = 111
  //    word  -> offset = 101/110/111
  //    dword -> offset != 000
  val addr_unaligned = Mux(uop.fu_code === s"b$FU_MEM".U,
    MuxLookup(uop.mem_size, false.B, Array(
      s"b$MEM_HALF".U  -> (addr_offset === "b111".U),
      s"b$MEM_WORD".U  -> (addr_offset.asUInt() > "b100".U),
      s"b$MEM_DWORD".U -> (addr_offset =/= "b000".U)
    )), false.B)
  // currently we just skip the memory access with unaligned address
  // todo: add this exception in CSR unit in the future

  st_req.bits.addr  := Mux(mmio, addr, Cat(addr(31, 3), Fill(3, 0.U)))
  st_req.bits.wdata := (wdata << (addr_offset << 3))(63, 0)
  st_req.bits.wmask := mask & ((wmask << addr_offset)(7, 0))
  st_req.bits.wen   := true.B
  st_req.bits.size  := Mux(mmio, uop.mem_size, s"b$MEM_DWORD".U)
  st_req.bits.id    := 0.U
  st_req.valid      := uop.valid && (state === s_idle) && !addr_unaligned &&
                       is_store && (lsu_update || !completed)

  st_resp.ready     := st_resp.valid

  ld_req.bits.addr  := Mux(mmio, addr, Cat(addr(31, 3), Fill(3, 0.U)))
  ld_req.bits.wdata := 0.U
  ld_req.bits.wmask := 0.U
  ld_req.bits.wen   := false.B
  ld_req.bits.size  := Mux(mmio, uop.mem_size, s"b$MEM_DWORD".U)
  ld_req.bits.id    := 0.U
  ld_req.valid      := uop.valid && (state === s_idle) && !addr_unaligned &&
                       is_load && (lsu_update || !completed)

  ld_resp.ready     := ld_resp.valid

  val load_data = WireInit(UInt(64.W), 0.U)
  val store_valid = RegInit(false.B)

  switch (state) {
    is (s_idle) {
      when (ld_req.fire()) {
        state := s_wait_r
        store_valid := false.B
      } .elsewhen (st_req.fire()) {
        state := s_wait_w
        store_valid := true.B
      }
      when (addr_unaligned) {
        completed := true.B
      }
    }
    is (s_wait_r) {
      when (ld_resp.fire()) {
        load_data := ld_resp.bits.rdata >> (addr_offset << 3)
        if (ZhoushanConfig.DebugLsu) {
          printf("%d: [LOAD ] pc=%x addr=%x rdata=%x -> %x\n", DebugTimer(),
                 uop.pc, addr, ld_resp.bits.rdata, ld_resp.bits.rdata >> (addr_offset << 3))
        }
        completed := true.B
        state := s_idle
      }
    }
    is (s_wait_w) {
      when (st_resp.fire()) {
        if (ZhoushanConfig.DebugLsu) {
          printf("%d: [STORE] pc=%x addr=%x wdata=%x -> %x wmask=%x\n", DebugTimer(),
                 uop.pc, addr, in2, st_req.bits.wdata, st_req.bits.wmask)
        }
        completed := true.B
        state := s_idle
      }
    }
  }

  // when flush, invalidate the current load/store request
  when (io.flush) {
    completed := true.B
    state := s_idle
  }

  val ld_out = Wire(UInt(64.W))
  val ldu_out = Wire(UInt(64.W))
  val load_out = Wire(UInt(64.W))

  ld_out := Mux(uop.mem_code === s"b$MEM_LD".U, MuxLookup(uop.mem_size, 0.U, Array(
    s"b$MEM_BYTE".U  -> Cat(Fill(56, load_data(7)), load_data(7, 0)),
    s"b$MEM_HALF".U  -> Cat(Fill(48, load_data(15)), load_data(15, 0)),
    s"b$MEM_WORD".U  -> Cat(Fill(32, load_data(31)), load_data(31, 0)),
    s"b$MEM_DWORD".U -> load_data
  )), 0.U)

  ldu_out := Mux(uop.mem_code === s"b$MEM_LDU".U, MuxLookup(uop.mem_size, 0.U, Array(
    s"b$MEM_BYTE".U  -> Cat(Fill(56, 0.U), load_data(7, 0)),
    s"b$MEM_HALF".U  -> Cat(Fill(48, 0.U), load_data(15, 0)),
    s"b$MEM_WORD".U  -> Cat(Fill(32, 0.U), load_data(31, 0)),
    s"b$MEM_DWORD".U -> load_data
  )), 0.U)

  load_out := MuxLookup(uop.mem_code, 0.U, Array(
    s"b$MEM_LD".U  -> ld_out,
    s"b$MEM_LDU".U -> ldu_out
  ))

  io.ecp := 0.U.asTypeOf(new ExCommitPacket)
  io.ecp.store_valid := store_valid
  io.ecp.mmio := mmio
  io.ecp.rd_data := load_out
  io.busy := ld_req.valid || st_req.valid || (state === s_wait_r && !ld_resp.fire()) || (state === s_wait_w && !st_resp.fire())

}
