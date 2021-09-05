package zhoushan

import chisel3._

abstract class Packet extends Bundle {
  def flush() : Unit
}

class InstPacket extends Packet {
  val pc = UInt(32.W)
  val inst = UInt(32.W)
  val pred_br = Bool()
  val pred_pc = UInt(32.W)
  val valid = Bool()
  def flush() : Unit = {
    pc := 0.U
    inst := 0.U
    pred_br := false.B
    pred_pc := 0.U
    valid := false.B
  }
}

class ExPacket extends Packet {
  val uop = new MicroOp
  val rs1_data = UInt(64.W)
  val rs2_data = UInt(64.W)
  def flush() : Unit = {
    uop.nop()
    rs1_data := 0.U
    rs2_data := 0.U
  }
}

class CommitPacket extends Packet {
  val uop = new MicroOp
  val rd_data = UInt(64.W)
  def flush() : Unit = {
    uop.nop()
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
