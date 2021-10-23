/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

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
    val out = Output(UInt(64.W))
    val mmio = Output(Bool())
    val busy = Output(Bool())
    val dmem = new CacheBusIO
  })

  val lsu_update = io.uop.valid

  val uop = HoldUnless(io.uop, lsu_update)
  val in1 = HoldUnless(io.in1, lsu_update)
  val in2 = HoldUnless(io.in2, lsu_update)

  val completed = RegInit(true.B)
  when (lsu_update) {
    completed := false.B
  }

  val is_mem = (uop.fu_code === s"b$FU_MEM".U)
  val is_load = (uop.mem_code === s"b$MEM_LD".U || uop.mem_code === s"b$MEM_LDU".U)
  val is_store = (uop.mem_code === s"b$MEM_ST".U)

  val s_idle :: s_wait :: Nil = Enum(2)
  val state = RegInit(s_idle)

  val req = io.dmem.req
  val resp = io.dmem.resp

  val addr = (in1(31, 0) + uop.imm)(31, 0)
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

  req.bits.addr  := Mux(mmio, addr, Cat(addr(31, 3), Fill(3, 0.U)))
  req.bits.wdata := (wdata << (addr_offset << 3))(63, 0)
  req.bits.wmask := mask & ((wmask << addr_offset)(7, 0))
  req.bits.wen   := is_store
  req.bits.size  := Mux(mmio, uop.mem_size, s"b$MEM_DWORD".U)
  req.valid      := uop.valid && (state === s_idle) && !addr_unaligned &&
                    is_mem && (lsu_update || !completed)

  resp.ready     := resp.valid

  val load_data = WireInit(UInt(64.W), 0.U)
  val ld_out = Wire(UInt(64.W))
  val ldu_out = Wire(UInt(64.W))
  val load_out = Wire(UInt(64.W))

  switch (state) {
    is (s_idle) {
      when (req.fire()) {
        state := s_wait
      }
      when (addr_unaligned) {
        completed := true.B
      }
    }
    is (s_wait) {
      when (resp.fire()) {
        load_data := resp.bits.rdata >> (addr_offset << 3)
        if (ZhoushanConfig.DebugLsu) {
          when (is_load) {
            printf("%d: [LOAD ] pc=%x addr=%x rdata=%x -> %x\n", DebugTimer(),
                   uop.pc, addr, resp.bits.rdata, resp.bits.rdata >> (addr_offset << 3))
          }
          when (is_store) {
            printf("%d: [STORE] pc=%x addr=%x wdata=%x -> %x wmask=%x\n", DebugTimer(),
                   uop.pc, addr, in2, req.bits.wdata, req.bits.wmask)
          }
        }
        completed := true.B
        state := s_idle
      }
    }
  }

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

  io.mmio := mmio
  io.out := load_out
  io.busy := req.valid || (state === s_wait && !resp.fire())

}
