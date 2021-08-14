package zhoushan

import chisel3._

class Decode extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val uop = Output(new MicroOp())
  })

  val uop = io.uop
  uop.pc := 0.U
  uop.inst := io.inst
  uop.uopc := 0.U
  uop.rs1_addr := 0.U
  uop.rs2_addr := 0.U
  uop.rd_addr := 0.U
  uop.rd_en := false.B
  uop.imm := 0.U
  uop.valid := false.B

}