package zhoushan

import chisel3._
import chisel3.util._

class InstPacket extends Bundle {
  val pc = Output(UInt(32.W))
  val inst = Output(UInt(32.W))
  val pred_br = Output(Bool())
  val pred_pc = Output(UInt(32.W))
}

class InstPacketVec extends Bundle with ZhoushanConfig {
  val enq_width = FetchWidth
  val vec = Vec(enq_width, Valid(new InstPacket))
}

class InstBuffer extends Module with ZhoushanConfig {
  val entries = InstBufferSize
  val enq_width = FetchWidth
  val deq_width = DecodeWidth

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new InstPacketVec))
    val out = Vec(deq_width, Decoupled(new InstPacket))
    val flush = Input(Bool())
  })

  val idx_width = log2Ceil(entries)
  val addr_width = idx_width + 1  // MSB is flag bit
  def getIdx(x: UInt): UInt = x(idx_width - 1, 0)
  def getFlag(x: UInt): Bool = x(addr_width - 1).asBool()

  val buf = SyncReadMem(entries, new InstPacket, SyncReadMem.WriteFirst)

  val enq_vec = RegInit(VecInit((0 until enq_width).map(_.U(addr_width.W))))
  val deq_vec = RegInit(VecInit((0 until deq_width).map(_.U(addr_width.W))))
  val enq_ptr = getIdx(enq_vec(0))
  val deq_ptr = getIdx(deq_vec(0))
  val enq_flag = getFlag(enq_vec(0))
  val deq_flag = getFlag(deq_vec(0))

  val count = Mux(enq_flag === deq_flag, enq_ptr - deq_ptr, entries.U + enq_ptr - deq_ptr)
  val enq_ready = RegInit(true.B)

  val num_enq = Mux(io.in.fire(), PopCount(io.in.bits.vec.map(_.valid)), 0.U)
  val num_deq = PopCount(io.out.map(_.fire()))

  val num_try_deq = Mux(count >= deq_width.U, deq_width.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = Mux(io.out(0).ready, num_after_enq - num_try_deq, num_after_enq)

  enq_ready := (entries - enq_width).U >= next_valid_entry

  // enq

  val offset = Wire(Vec(enq_width, UInt(log2Ceil(enq_width + 1).W)))
  for (i <- 0 until enq_width) {
    if (i == 0) {
      offset(i) := 0.U
    } else {
      offset(i) := PopCount(io.in.bits.vec(0).valid)  // todo: this only works for 2-way
    }
  }

  for (i <- 0 until enq_width) {
    val enq = Wire(new InstPacket)
    enq.pc      := io.in.bits.vec(i).bits.pc
    enq.inst    := io.in.bits.vec(i).bits.inst
    enq.pred_br := io.in.bits.vec(i).bits.pred_br
    enq.pred_pc := io.in.bits.vec(i).bits.pred_pc

    when (io.in.bits.vec(i).valid && io.in.fire() && !io.flush) {
      buf.write(getIdx(enq_vec(offset(i))), enq)
    }
  }

  val next_enq_vec = VecInit(enq_vec.map(_ + num_enq))

  when (io.in.fire() && !io.flush) {
    enq_vec := next_enq_vec
  }

  io.in.ready := enq_ready

  // deq

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count)(deq_width - 1, 0) - 1.U)
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  for (i <- 0 until deq_width) {
    val deq = buf.read(getIdx(next_deq_vec(i)))
    io.out(i).bits.pc      := deq.pc
    io.out(i).bits.inst    := deq.inst
    io.out(i).bits.pred_br := deq.pred_br
    io.out(i).bits.pred_pc := deq.pred_pc
    io.out(i).valid := valid_vec(i)
  }

  // flush

  when (io.flush) {
    enq_ready := false.B
    enq_vec := VecInit((0 until enq_width).map(_.U(addr_width.W)))
    deq_vec := VecInit((0 until deq_width).map(_.U(addr_width.W)))
  }

}
