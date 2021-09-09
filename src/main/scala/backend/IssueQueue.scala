package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class MicroOpVec(vec_width: Int) extends Bundle {
  val vec = Vec(vec_width, Output(new MicroOp))
}

class IssueQueue extends Module with ZhoushanConfig {
  val entries = IssueQueueSize
  val enq_width = DecodeWidth
  val deq_width = IssueWidth

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MicroOpVec(enq_width)))
    val out = Decoupled(new MicroOpVec(deq_width))
    val flush = Input(Bool())
  })

  val idx_width = log2Ceil(entries)
  val addr_width = idx_width + 1  // MSB is flag bit
  def getIdx(x: UInt): UInt = x(idx_width - 1, 0)
  def getFlag(x: UInt): Bool = x(addr_width - 1).asBool()

  val buf = SyncReadMem(entries, new MicroOp, SyncReadMem.WriteFirst)

  val enq_vec = RegInit(VecInit((0 until enq_width).map(_.U(addr_width.W))))
  val deq_vec = RegInit(VecInit((0 until deq_width).map(_.U(addr_width.W))))
  val enq_ptr = getIdx(enq_vec(0))
  val deq_ptr = getIdx(deq_vec(0))
  val enq_flag = getFlag(enq_vec(0))
  val deq_flag = getFlag(deq_vec(0))

  val count = Mux(enq_flag === deq_flag, enq_ptr - deq_ptr, entries.U + enq_ptr - deq_ptr)
  val enq_ready = RegInit(true.B)

  val num_enq = Mux(io.in.fire(), PopCount(io.in.bits.vec.map(_.valid)), 0.U)
  val num_deq = Mux(io.out.fire(), PopCount(io.out.bits.vec.map(_.valid)), 0.U)

  // even though deq_width = 2, we may deq only 1 instruction each time
  val num_try_deq = Mux(count >= 1.U, 1.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = Mux(io.out.ready, num_after_enq - num_try_deq, num_after_enq)

  enq_ready := (entries - enq_width).U >= next_valid_entry

  // enq

  val offset = Wire(Vec(enq_width, UInt(log2Ceil(enq_width + 1).W)))
  for (i <- 0 until enq_width) {
    if (i == 0) {
      offset(i) := 0.U
    } else {
      // todo: currently only support 2-way
      offset(i) := PopCount(io.in.bits.vec(0).valid)
    }
  }

  for (i <- 0 until enq_width) {
    val enq = Wire(new MicroOp)
    enq := io.in.bits.vec(i)

    when (enq.valid && io.in.fire() && !io.flush) {
      buf.write(getIdx(enq_vec(offset(i))), enq)
    }
  }

  val next_enq_vec = VecInit(enq_vec.map(_ + num_enq))

  when (io.in.fire() && !io.flush) {
    enq_vec := next_enq_vec
  }

  io.in.ready := enq_ready

  // deq

  val ready_vec = WireInit(VecInit(Seq.fill(deq_width)(false.B)))

  // currently we only consider 2-way in-order superscalar issue
  val uop0 = io.out.bits.vec(0)
  ready_vec(0) := true.B
  val uop1 = io.out.bits.vec(1)
  val uop1_rs1_from_uop0_rd = uop0.rd_en && (uop1.rs1_src === RS_FROM_RF) && (uop1.rs1_addr === uop0.rd_addr)
  val uop1_rs2_from_uop0_rd = uop0.rd_en && (uop1.rs2_src === RS_FROM_RF) && (uop1.rs2_addr === uop0.rd_addr)
  ready_vec(1) := (uop1.fu_code === FU_ALU) && !uop1_rs1_from_uop0_rd && !uop1_rs2_from_uop0_rd

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count)(deq_width - 1, 0) - 1.U)
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  for (i <- 0 until deq_width) {
    val deq = buf.read(getIdx(next_deq_vec(i)))
    io.out.bits.vec(i) := deq
    io.out.bits.vec(i).valid := deq.valid && valid_vec(i) && ready_vec(i)
  }

  io.out.valid := Cat(io.out.bits.vec.map(_.valid)).orR

  // flush

  when (io.flush) {
    enq_ready := true.B
    enq_vec := VecInit((0 until enq_width).map(_.U(addr_width.W)))
    deq_vec := VecInit((0 until deq_width).map(_.U(addr_width.W)))
  }

}
