package zhoushan

import chisel3._

trait Constants {
  val UOPC_ADDI = 1.U(6.W)
}

class MicroOp extends Bundle {
  val pc = UInt(32.W)
  val inst = UInt(32.W)
  val uopc = UInt(6.W)
  val rs1_addr = UInt(5.W)
  val rs2_addr = UInt(5.W)
  val rd_addr = UInt(5.W)
  val rd_en = Bool()
  val imm = UInt(64.W)
  val valid = Bool()
}
