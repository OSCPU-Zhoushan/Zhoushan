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
import zhoushan.Constant._

class Alu extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val jmp_packet = Output(new JmpPacket)
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val shamt = Wire(UInt(6.W))
  shamt := Mux(uop.w_type, in2(4, 0).asUInt(), in2(5, 0))

  val alu_out_0 = Wire(UInt(64.W))
  val alu_out = Wire(UInt(64.W))
  val jmp = Wire(Bool())
  val jmp_pc = Wire(UInt(32.W))
  val jmp_out = WireInit(UInt(64.W), 0.U)

  alu_out_0 := MuxLookup(uop.alu_code, 0.U, Array(
    s"b$ALU_ADD".U  -> (in1 + in2).asUInt(),
    s"b$ALU_SUB".U  -> (in1 - in2).asUInt(),
    s"b$ALU_SLT".U  -> (in1.asSInt() < in2.asSInt()).asUInt(),
    s"b$ALU_SLTU".U -> (in1 < in2).asUInt(),
    s"b$ALU_XOR".U  -> (in1 ^ in2).asUInt(),
    s"b$ALU_OR".U   -> (in1 | in2).asUInt(),
    s"b$ALU_AND".U  -> (in1 & in2).asUInt(),
    s"b$ALU_SLL".U  -> ((in1 << shamt)(63, 0)).asUInt(),
    s"b$ALU_SRL".U  -> (in1.asUInt() >> shamt).asUInt(),
    s"b$ALU_SRA".U  -> (in1.asSInt() >> shamt).asUInt()
  ))

  alu_out := Mux(uop.w_type, SignExt32_64(alu_out_0(31, 0)), alu_out_0)

  jmp := MuxLookup(uop.jmp_code, false.B, Array(
    s"b$JMP_JAL".U  -> true.B,
    s"b$JMP_JALR".U -> true.B,
    s"b$JMP_BEQ".U  -> (in1 === in2),
    s"b$JMP_BNE".U  -> (in1 =/= in2),
    s"b$JMP_BLT".U  -> (in1.asSInt() < in2.asSInt()),
    s"b$JMP_BGE".U  -> (in1.asSInt() >= in2.asSInt()),
    s"b$JMP_BLTU".U -> (in1.asUInt() < in2.asUInt()),
    s"b$JMP_BGEU".U -> (in1.asUInt() >= in2.asUInt())
  )) && (uop.fu_code === s"b$FU_JMP".U)

  jmp_pc := Mux(uop.jmp_code === s"b$JMP_JALR".U, in1(31, 0), uop.pc) + uop.imm

  when (uop.jmp_code === s"b$JMP_JAL".U || uop.jmp_code === s"b$JMP_JALR".U) {
    jmp_out := uop.pc + 4.U
  }

  io.out := Mux(uop.fu_code === s"b$FU_ALU".U, alu_out, jmp_out)
  io.jmp_packet.jmp := jmp
  io.jmp_packet.jmp_pc := jmp_pc
}
