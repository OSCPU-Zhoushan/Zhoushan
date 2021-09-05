package zhoushan

import chisel3._
import chisel3.util._

class InstPacket extends Bundle {
  val pc = Output(UInt(32.W))
  val inst = Output(UInt(32.W))
  val pred_br = Output(Bool())
  val pred_pc = Output(UInt(32.W))
}

class InstBuffer extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new InstPacket))
    val out = Decoupled(new InstPacket)
  })

  val queue = Module(new Queue(gen = new InstPacket, entries = 8, pipe = true, flow = true))

  queue.io.enq <> io.in
  queue.io.deq <> io.out

}
