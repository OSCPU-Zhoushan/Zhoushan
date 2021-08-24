package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

trait Ext {
  def signExt32_64(x: UInt) : UInt = Cat(Fill(32, x(31)), x)
  def zeroExt32_64(x: UInt) : UInt = Cat(Fill(32, 0.U), x)
}

class Execution extends Module with Ext {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val rs1_data = Input(UInt(64.W))
    val rs2_data = Input(UInt(64.W))
    val result = Output(UInt(64.W))
    val busy = Output(Bool())
    val jmp_packet = Output(new JmpPacket)
    val dmem = if (Settings.UseAxi) (new SimpleAxiIO) else (Flipped(new RamIO))
  })

  val uop = io.uop
  val in1_0, in1, in2_0, in2 = Wire(UInt(64.W))

  in1_0 := MuxLookup(uop.rs1_src, 0.U, Array(
    RS_FROM_RF  -> io.rs1_data,
    RS_FROM_IMM -> signExt32_64(uop.imm),
    RS_FROM_PC  -> zeroExt32_64(uop.pc),
    RS_FROM_NPC -> zeroExt32_64(uop.npc)
  )).asUInt()

  in2_0 := MuxLookup(uop.rs2_src, 0.U, Array(
    RS_FROM_RF  -> io.rs2_data,
    RS_FROM_IMM -> signExt32_64(uop.imm),
    RS_FROM_PC  -> zeroExt32_64(uop.pc),
    RS_FROM_NPC -> zeroExt32_64(uop.npc)
  )).asUInt()

  in1 := Mux(uop.w_type, Mux(uop.alu_code === ALU_SRL, zeroExt32_64(in1_0(31, 0)), signExt32_64(in1_0(31, 0))), in1_0)
  in2 := Mux(uop.w_type, signExt32_64(in2_0(31, 0)), in2_0)

  val alu = Module(new Alu)
  alu.io.uop := uop
  alu.io.in1 := in1
  alu.io.in2 := in2

  val lsu = Module(if (Settings.UseAxi) (new Lsu) else (new LsuWithRamHelper))
  lsu.io.uop := uop
  lsu.io.in1 := in1
  lsu.io.in2 := in2
  lsu.io.dmem <> io.dmem

  val csr = Module(new Csr)
  csr.io.uop := uop
  csr.io.in1 := in1

  val busy = lsu.io.busy

  val jmp = MuxLookup(uop.fu_code, false.B, Array(
    FU_JMP -> alu.io.jmp,
    FU_CSR -> csr.io.jmp
  ))

  val jmp_pc = MuxLookup(uop.fu_code, 0.U, Array(
    FU_JMP -> alu.io.jmp_pc,
    FU_CSR -> csr.io.jmp_pc
  ))

  io.result := alu.io.out | lsu.io.out | csr.io.out
  io.busy := busy

  val mis_predict = Mux(jmp, (uop.pred_br && (jmp_pc =/= uop.pred_pc)) || !uop.pred_br, uop.pred_br)

  io.jmp_packet.valid := (uop.fu_code === FU_JMP) || (uop.csr_code === CSR_ECALL) ||
                         (uop.csr_code === CSR_MRET)
  io.jmp_packet.inst_pc := uop.pc
  io.jmp_packet.jmp := jmp
  io.jmp_packet.jmp_pc := jmp_pc
  io.jmp_packet.mis := io.jmp_packet.valid && mis_predict
}
