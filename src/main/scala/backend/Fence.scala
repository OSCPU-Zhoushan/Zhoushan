package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._

class Fence extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
  })

  val uop = io.uop

  val fence_i = uop.valid && (uop.fu_code === FU_SYS) && (uop.sys_code === SYS_FENCEI)
  BoringUtils.addSource(fence_i, "fence_i")

}
