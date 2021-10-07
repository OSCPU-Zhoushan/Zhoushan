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
  ))

  jmp_pc := Mux(uop.jmp_code === s"b$JMP_JALR".U, in1(31, 0), uop.pc) + uop.imm

  npc_to_rd := MuxLookup(uop.jmp_code, 0.U, Array(
    s"b$JMP_JAL".U  -> ZeroExt32_64(uop.npc),
    s"b$JMP_JALR".U -> ZeroExt32_64(uop.npc)
  ))

  io.ecp.store_valid := false.B
  io.ecp.mmio := false.B
  io.ecp.jmp_valid := (uop.fu_code === s"b$FU_JMP".U)
  io.ecp.jmp := jmp
  io.ecp.jmp_pc := jmp_pc
  io.ecp.mis := Mux(jmp,
                    (uop.pred_br && (jmp_pc =/= uop.pred_bpc)) || !uop.pred_br,
                    uop.pred_br)
  io.ecp.rd_data := Mux(uop.fu_code === s"b$FU_ALU".U, alu_out, npc_to_rd)
}
