package zhoushan

import chisel3._

abstract class Packet extends Bundle {
  def flush() : Unit
}

class ExPacket extends Packet {
  val uop = new MicroOp
  val rs1_data = UInt(64.W)
  val rs2_data = UInt(64.W)
  def flush() : Unit = {
    uop := 0.U.asTypeOf(new MicroOp)
    rs1_data := 0.U
    rs2_data := 0.U
  }
}

class CommitPacket extends Packet {
  val uop = new MicroOp
  val rd_data = UInt(64.W)
  def flush() : Unit = {
    uop := 0.U.asTypeOf(new MicroOp)
    rd_data := 0.U
  }
}

class PipelineReg[T <: Packet](packet: T) extends Module {
  val io = IO(new Bundle {
    val in = Input(packet)
    val out = Output(packet)
    val flush = Input(Bool())
    val stall = Input(Bool())
  })

  val reg = RegInit(packet, 0.U.asTypeOf(packet))

  when (io.flush) {
    reg.flush()
  } .elsewhen (!io.stall) {
    reg := io.in
  }

  io.out := reg
}