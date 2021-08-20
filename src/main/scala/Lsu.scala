package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class Lsu extends Module with Ext {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val busy = Output(Bool())
    val dmem = Flipped(new RamIO)
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val addr = in1 + SignExt32_64(uop.imm)
  val addr_offset = addr(2, 0)
  val addr_nextline = addr + "b1000".U
  val addr_offset_nextline = (~addr_offset) + 1.U;

  val mask = MuxLookup(addr_offset, 0.U, Array(
    "b000".U -> "hffffffffffffffff".U,
    "b001".U -> "hffffffffffffff00".U,
    "b010".U -> "hffffffffffff0000".U,
    "b011".U -> "hffffffffff000000".U,
    "b100".U -> "hffffffff00000000".U,
    "b101".U -> "hffffff0000000000".U,
    "b110".U -> "hffff000000000000".U,
    "b111".U -> "hff00000000000000".U
  ))
  val mask_nextline = ~mask;
  val wmask = MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> "h00000000000000ff".U,
    MEM_HALF  -> "h000000000000ffff".U,
    MEM_WORD  -> "h00000000ffffffff".U,
    MEM_DWORD -> "hffffffffffffffff".U
  ))

  val load_data = Wire(UInt(64.W))
  val load_data_reg = RegNext(load_data)

  // may need to read/write memory in 2 lines
  val stall = RegInit(false.B)
  // half  -> offset = 111
  // word  -> offset = 101/110/111
  // dword -> offset != 000
  stall := Mux(uop.fu_code === FU_MEM, MuxLookup(uop.mem_size, false.B, Array(
    MEM_HALF  -> (addr_offset === "b111".U),
    MEM_WORD  -> (addr_offset.asUInt() > "b100".U),
    MEM_DWORD -> (addr_offset =/= "b000".U)
  )), false.B)

  when (stall) {
    stall := false.B
  }

  // 0 = normal / read line 1, 1 = read line 2
  val dmem_state = RegInit(0.U(1.W))
  when (dmem_state === 0.U) {
    when (stall) { dmem_state := 1.U }
    io.dmem.addr := addr
    load_data := io.dmem.rdata >> (addr_offset << 3)
    io.dmem.wmask := mask & ((wmask << (addr_offset << 3))(63, 0))
    io.dmem.wdata := (in2 << (addr_offset << 3))(63, 0)
  } .otherwise {
    io.dmem.addr := addr_nextline
    load_data := load_data_reg | (io.dmem.rdata << (addr_offset_nextline << 3))
    io.dmem.wmask := mask_nextline & (wmask >> (addr_offset_nextline << 3)).asUInt()
    io.dmem.wdata := (in2 >> (addr_offset_nextline << 3)).asUInt()
  }

  io.dmem.en := (uop.fu_code === FU_MEM)
  io.dmem.wen := (uop.mem_code === MEM_ST)

  val ld_out = Wire(UInt(64.W))
  val ldu_out = Wire(UInt(64.W))
  val load_out = Wire(UInt(64.W))

  ld_out := Mux(uop.mem_code === MEM_LD, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, load_data(7)), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, load_data(15)), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, load_data(31)), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  ldu_out := Mux(uop.mem_code === MEM_LDU, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, 0.U), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, 0.U), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, 0.U), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  load_out := MuxLookup(uop.mem_code, 0.U, Array(
    MEM_LD  -> ld_out,
    MEM_LDU -> ldu_out
  ))

  io.out := load_out
  io.busy := stall
}
