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
    val flush = Input(Bool())
  })

  val fifo = Module(new Fifo(gen = new InstPacket, entries = 8, pipe = true, flow = true, useSyncReadMem = true, hasFlush = true))

  fifo.io.enq <> io.in
  fifo.io.deq <> io.out
  fifo.io.flush.get := io.flush

}
