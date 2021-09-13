package zhoushan

import chisel3._
import chisel3.util._

object DebugTimer {
  def apply() = {
    val c = RegInit(0.U(64.W))
    c := c + 1.U
    c
  }
}

object MaskExpand {
  def apply(x: UInt) = Cat(x.asBools.map(Fill(8, _)).reverse)
}

object MaskData {
  def apply(old_data: UInt, new_data: UInt, mask: UInt) = {
    (new_data & mask) | (old_data & ~mask)
  }
}

object SignExt32_64 {
  def apply(x: UInt): UInt = Cat(Fill(32, x(31)), x)
}

object ZeroExt32_64 {
  def apply(x: UInt): UInt = Cat(Fill(32, 0.U), x)
}
