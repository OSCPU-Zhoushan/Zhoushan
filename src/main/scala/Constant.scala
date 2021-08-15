package zhoushan

import chisel3._

trait Constant {
  val Y = true.B
  val N = false.B

  val RS_X        = 0.asUInt(3.W)
  val RS_FROM_RF  = 1.asUInt(3.W)
  val RS_FROM_IMM = 2.asUInt(3.W)

  val FU_X    = 0.asUInt(2.W)
  val FU_ALU  = 1.asUInt(2.W)

  val ALU_X   = 0.asUInt(4.W)
  val ALU_ADD = 1.asUInt(4.W)
}

object Constant extends Constant {

}