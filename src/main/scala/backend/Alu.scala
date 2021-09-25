package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class Alu extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val ecp = Output(new ExCommitPacket)
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val shamt = Wire(UInt(6.W))
  shamt := Mux(uop.w_type, in2(4, 0).asUInt(), in2(5, 0))

  val alu_out_0, alu_out = Wire(UInt(64.W))
  val jmp = Wire(Bool())
  val jmp_pc = Wire(UInt(32.W))
  val npc_to_rd = Wire(UInt(64.W))

  alu_out_0 := MuxLookup(uop.alu_code, 0.U, Array(
    ALU_ADD  -> (in1 + in2).asUInt(),
    ALU_SUB  -> (in1 - in2).asUInt(),
    ALU_SLT  -> (in1.asSInt() < in2.asSInt()).asUInt(),
    ALU_SLTU -> (in1 < in2).asUInt(),
    ALU_XOR  -> (in1 ^ in2).asUInt(),
    ALU_OR   -> (in1 | in2).asUInt(),
    ALU_AND  -> (in1 & in2).asUInt(),
    ALU_SLL  -> ((in1 << shamt)(63, 0)).asUInt(),
    ALU_SRL  -> (in1.asUInt() >> shamt).asUInt(),
    ALU_SRA  -> (in1.asSInt() >> shamt).asUInt()
  ))

  alu_out := Mux(uop.w_type, SignExt32_64(alu_out_0(31, 0)), alu_out_0)

  jmp := MuxLookup(uop.jmp_code, false.B, Array(
    JMP_JAL  -> true.B,
    JMP_JALR -> true.B,
    JMP_BEQ  -> (in1 === in2),
    JMP_BNE  -> (in1 =/= in2),
    JMP_BLT  -> (in1.asSInt() < in2.asSInt()),
    JMP_BGE  -> (in1.asSInt() >= in2.asSInt()),
    JMP_BLTU -> (in1.asUInt() < in2.asUInt()),
    JMP_BGEU -> (in1.asUInt() >= in2.asUInt())
  ))

  jmp_pc := Mux(uop.jmp_code === JMP_JALR, in1(31, 0), uop.pc) + uop.imm

  npc_to_rd := MuxLookup(uop.jmp_code, 0.U, Array(
    JMP_JAL  -> ZeroExt32_64(uop.npc),
    JMP_JALR -> ZeroExt32_64(uop.npc)
  ))

  io.ecp.store_valid := false.B
  io.ecp.mmio := false.B
  io.ecp.jmp_valid := (uop.fu_code === FU_JMP)
  io.ecp.jmp := jmp
  io.ecp.jmp_pc := jmp_pc
  io.ecp.rd_data := alu_out | npc_to_rd
}
