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
import zhoushan.RasConstant._

trait BpParameters {
  // Branch History Table
  val BhtWidth = 6
  val BhtSize = 64
  val BhtAddrSize = log2Up(BhtSize)
  // Global History Table
  val GhtWidth = 6
  // Pattern History Table
  val PhtLocal = true                         // PhtLocal is false -> GShare
  val ChtWidth = if (PhtLocal) BhtWidth else GhtWidth
  val PhtWidth = 8                            // only available for local PHT
  val PhtIndexSize = log2Up(PhtWidth)
  val PhtSize = 1 << ChtWidth
  val PhtAddrSize = ChtWidth
  // Branch Target Buffer
  val BtbAssociative = true
  val BtbSize = 64
  val BtbAddrSize = log2Up(BtbSize) -
                    (if (BtbAssociative) 2    // -2 due to 4-way associative
                    else 0)                   // -0 due to direct mapped
  val BtbTagSize = 30 - BtbAddrSize           // 32 - BtbAddrSize - 2
  // Return Address Stack
  val RasEnable = false
  val RasSize = 16
  val RasPtrSize = log2Up(RasSize)
}

class BranchPredictor extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    // from IF stage
    val pc = Input(UInt(32.W))
    val pc_en = Input(Bool())
    // from EX stage
    val jmp_packet = Input(new JmpPacket)
    // prediction result
    val pred_br = Vec(FetchWidth, Output(Bool()))
    val pred_bpc = Output(UInt(32.W))
    val pred_valid = Output(Bool())
  })

  val pc_base = Cat(io.pc(31, 3), Fill(3, 0.U))
  val pc = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(32.W))))
  for (i <- 0 until FetchWidth) {
    pc(i) := pc_base + (i * 4).U
  }
  val npc = pc_base + (4 * FetchWidth).U

  // todo: currently only support 2-way
  val pc_en = RegInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  pc_en(0) := io.pc_en && (io.pc(2) === 0.U)
  pc_en(1) := io.pc_en

  val jmp_packet = WireInit(0.U.asTypeOf(new JmpPacket))
  jmp_packet := io.jmp_packet
  // for system jump (e.g. interrupt, fence.i), don't update branch predictor
  jmp_packet.valid := io.jmp_packet.valid && !io.jmp_packet.sys

  val pred_br = WireInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  val pred_bpc = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(32.W))))

  // BHT definition
  val bht = Mem(BhtSize, UInt(BhtWidth.W))
  def bhtAddr(x: UInt) : UInt = x(1 + BhtAddrSize, 2)

  // BHT read logic
  val bht_raddr = pc.map(bhtAddr(_))
  val bht_rdata = bht_raddr.map(bht(_))

  // BHT update logic
  val bht_waddr = bhtAddr(jmp_packet.inst_pc)
  val bht_wrdata = bht(bht_waddr)
  when (jmp_packet.valid) {
    bht(bht_waddr) := Cat(jmp_packet.jmp.asUInt(), bht_wrdata(BhtWidth - 1, 1))
  }

  // BHT reset
  when (reset.asBool()) {
    for (i <- 0 until BhtSize) {
      bht(i) := 0.U
    }
  }

  // GHT definition
  val ght = RegInit(0.U(GhtWidth.W))

  // GHT read logic
  val ght_rdata = ght

  // GHT update logic
  when (jmp_packet.valid) {
    ght := Cat(jmp_packet.jmp.asUInt(), ght(GhtWidth - 1, 1))
  }

  // PHT definition
  val pht = Module(if (PhtLocal) new PatternHistoryTableLocal
                   else new PatternHistoryTableGlobal)
  def phtAddr(cht_data: UInt, x: UInt) : UInt = cht_data ^ x(1 + ChtWidth, 2)
  def phtIndex(x: UInt) : UInt = x(1 + ChtWidth + PhtIndexSize, 2 + ChtWidth)

  // PHT read logic
  val pht_rdirect = WireInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  for (i <- 0 until FetchWidth) {
    pht.io.raddr(i) := phtAddr(if (PhtLocal) bht_rdata(i) else ght_rdata, pc(i))
    pht.io.rindex(i) := phtIndex(pc(i))
    pht_rdirect(i) := pht.io.rdirect(i)
  }

  // PHT update logic
  pht.io.waddr := phtAddr(if (PhtLocal) bht_wrdata else ght_rdata, jmp_packet.inst_pc)
  pht.io.windex := phtIndex(jmp_packet.inst_pc)
  pht.io.wen := jmp_packet.valid
  pht.io.wjmp := jmp_packet.jmp

  // BTB definition
  val btb = Module(if (BtbAssociative) new BranchTargetBuffer4WayAssociative
                   else new BranchTargetBufferDirectMapped)
  def btbAddr(x: UInt) : UInt = x(1 + BtbAddrSize, 2)
  def btbTag(x: UInt) : UInt = x(1 + BtbAddrSize + BtbTagSize, 2 + BtbAddrSize)

  // BTB read logic
  val btb_rhit = WireInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  val btb_rtarget = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(32.W))))
  val btb_rras_type = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(2.W))))
  for (i <- 0 until FetchWidth) {
    btb.io.raddr(i) := btbAddr(pc(i))
    btb.io.ren(i) := pc_en(i)
    btb.io.rtag(i) := btbTag(pc(i))
    btb_rhit(i) := btb.io.rhit(i)
    btb_rtarget(i) := btb.io.rtarget(i)
    btb_rras_type(i) := btb.io.rras_type(i)
  }

  // BTB update logic
  btb.io.waddr := btbAddr(jmp_packet.inst_pc)
  btb.io.wen := jmp_packet.valid && jmp_packet.jmp
  btb.io.wtag := btbTag(jmp_packet.inst_pc)
  btb.io.wtarget := jmp_packet.jmp_pc
  btb.io.wras_type := jmp_packet.ras_type
  btb.io.wpc := jmp_packet.inst_pc            // debug

  // RAS definition
  val ras = Module(new ReturnAddressStack)

  // RAS push logic
  val ras_push_vec = Cat(btb_rras_type.map(isRasPush(_)).reverse) & Cat(btb_rhit.reverse) & Cat(pht_rdirect.reverse) & Cat(pc_en.reverse)
  val ras_push_idx = PriorityEncoder(ras_push_vec)
  ras.io.push_en := ((ras_push_vec.orR && !jmp_packet.mis) || (jmp_packet.mis && isRasPush(jmp_packet.ras_type))) && jmp_packet.valid
  ras.io.push_pc := 0.U
  ras.io.push_src_pc := RegNext(io.pc)        // debug
  ras.io.mis_inst_pc := jmp_packet.inst_pc    // debug
  for (i <- 0 until FetchWidth) {
    when (ras_push_idx === i.U) {
      ras.io.push_pc := RegNext(pc(i) + 4.U)
    }
  }
  when (jmp_packet.mis && isRasPush(jmp_packet.ras_type)) {
    ras.io.push_pc := jmp_packet.inst_pc + 4.U
  }

  // RAS pop logic
  val ras_pop_vec = Cat(btb_rras_type.map(isRasPop(_)).reverse) & Cat(btb_rhit.reverse) & Cat(pht_rdirect.reverse) & Cat(pc_en.reverse)
  val ras_pop_idx = PriorityEncoder(ras_pop_vec)
  ras.io.pop_en := ((ras_pop_vec.orR && !jmp_packet.mis) || (jmp_packet.mis && isRasPop(jmp_packet.ras_type))) && jmp_packet.valid
  ras.io.pop_src_pc := RegNext(io.pc)         // debug
  val ras_ret_en = Wire(Vec(FetchWidth, Bool()))
  val ras_ret_pc = Wire(Vec(FetchWidth, UInt(32.W)))
  for (i <- 0 until FetchWidth) {
    when (ras_pop_vec.orR && ras_pop_idx === i.U && !jmp_packet.mis) {
      ras_ret_en(i) := true.B
      ras_ret_pc(i) := ras.io.top_pc
    } .otherwise {
      ras_ret_en(i) := false.B
      ras_ret_pc(i) := 0.U
    }
  }

  // update pred results
  for (i <- 0 until FetchWidth) {
    when (jmp_packet.valid && jmp_packet.mis) {
      pred_br(i) := false.B
      pred_bpc(i) := Mux(jmp_packet.jmp, jmp_packet.jmp_pc, jmp_packet.inst_pc + 4.U)
    } .otherwise {
      when (pht_rdirect(i)) {
        pred_br(i) := btb_rhit(i)   // equivalent to Mux(btb_rhit, pht_rdirect, false.B)
        pred_bpc(i) := Mux(btb_rhit(i), btb_rtarget(i), RegNext(npc))
      } .otherwise {
        pred_br(i) := false.B
        pred_bpc(i) := RegNext(npc)
      }
      if (RasEnable) {
        // RAS has the highest prediction priority
        when (ras_ret_en(i)) {
          pred_br(i) := true.B
          pred_bpc(i) := ras_ret_pc(i)
        }
      }
    }
  }

  for (i <- 0 until FetchWidth) {
    io.pred_br(i) := Mux(pc_en(i), pred_br(i), false.B)
  }
  io.pred_bpc := MuxLookup(Cat(pred_br.reverse), 0.U, Array(
    "b11".U -> pred_bpc(0),
    "b01".U -> pred_bpc(0),
    "b10".U -> pred_bpc(1)
  ))
  io.pred_valid := RegNext(io.pc_en)

}
