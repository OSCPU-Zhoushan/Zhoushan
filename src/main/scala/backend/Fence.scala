package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._

class Fence extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val ecp = Output(new ExCommitPacket)
  })

  val uop = io.uop

  val fence_i = uop.valid && (uop.fu_code === s"b$FU_SYS".U) && (uop.sys_code === s"b$SYS_FENCEI".U)
  BoringUtils.addSource(fence_i, "fence_i")

  /* when fence.i is commited
   *  1. mark it as a system jump
   *  2. flush the pipeline
   *  3. go to the instruction following fence.i
   */

  io.ecp := 0.U.asTypeOf(new ExCommitPacket)
  io.ecp.jmp_valid := true.B
  io.ecp.jmp := true.B
  io.ecp.jmp_pc := uop.npc
  io.ecp.mis := true.B

}
