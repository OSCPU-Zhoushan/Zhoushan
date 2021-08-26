package zhoushan

import chisel3._

object Settings {
  val UseAxi = true
  val ClintAddrBase = "h0000000002000000".U
  val ClintAddrSize = "h0000000000010000".U
}
