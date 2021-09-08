package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class Rob extends Module with ZhoushanConfig {
  val entries = RobSize
  val enq_width = DecodeWidth
  val backend_width = 3
  val deq_width = DecodeWidth

  val io = IO(new Bundle {
    // Dispatch stage
    val in = Flipped(Decoupled(new MicroOpVec(enq_width)))
    val out = Decoupled(new MicroOpVec(enq_width))
    // Execution stage (last clock cyle of execution)
    val complete = Vec(backend_width, Input(new MicroOp))
    // Commit stage
    val commit = Vec(deq_width, Output(new MicroOp))
    val jmp_packet = Output(new JmpPacket)
    val flush = Input(Bool())
  })

  val idx_width = log2Ceil(entries)
  val addr_width = idx_width + 1  // MSB is flag bit
  def getIdx(x: UInt): UInt = x(idx_width - 1, 0)
  def getFlag(x: UInt): Bool = x(addr_width - 1).asBool()

  val rob = SyncReadMem(entries, new MicroOp, SyncReadMem.WriteFirst)

  val enq_vec = RegInit(VecInit((0 until enq_width).map(_.U(addr_width.W))))
  val deq_vec = RegInit(VecInit((0 until deq_width).map(_.U(addr_width.W))))
  val enq_ptr = getIdx(enq_vec(0))
  val deq_ptr = getIdx(deq_vec(0))
  val enq_flag = getFlag(enq_vec(0))
  val deq_flag = getFlag(deq_vec(0))

  val count = Mux(enq_flag === deq_flag, enq_ptr - deq_ptr, entries.U + enq_ptr - deq_ptr)
  val enq_ready = RegInit(true.B)

  val num_enq = Mux(io.in.fire(), PopCount(io.in.bits.vec.map(_.valid)), 0.U)
  val num_deq = PopCount(io.commit.map(_.valid))

  val num_try_deq = Mux(count >= deq_width.U, deq_width.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = num_after_enq - num_try_deq

  enq_ready := (entries - enq_width).U >= next_valid_entry

  val complete = RegInit(VecInit(Seq.fill(RobSize)(false.B)))

  /* --------------- enq ----------------- */

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

    val rob_addr = getIdx(enq_vec(offset(i)))
    val enq_out = RegInit(0.U.asTypeOf(new MicroOp))

    when (io.in.bits.vec(i).valid && io.in.fire() && !io.flush) {
      // write to rob
      rob.write(rob_addr, enq)
      // write "false" to complete vector
      complete(rob_addr) := false.B
      // pass the enq result to out side
      enq_out := io.in.bits.vec(i)
      enq_out.rob_addr := rob_addr
      io.out.bits.vec(i) := enq_out
    } .otherwise {
      io.out.bits.vec(i) := 0.U.asTypeOf(new MicroOp)
    }
  }

  val next_enq_vec = VecInit(enq_vec.map(_ + num_enq))

  when (io.in.fire() && !io.flush) {
    enq_vec := next_enq_vec
  }

  io.in.ready := enq_ready && io.out.ready
  io.out.valid := io.in.valid

  // mark as completed
  for (i <- 0 until backend_width) {
    val rob_addr = io.complete(i).rob_addr
    complete(rob_addr) := io.complete(i).valid
  }

  /* --------------- deq ----------------- */

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count)(deq_width - 1, 0) - 1.U) 
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  // set the complete mask
  val complete_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  for (i <- 0 until deq_width) {
    val rob_addr = getIdx(next_deq_vec(i))
    if (i == 0) {
      complete_mask(i) := complete(rob_addr)
    } else {
      complete_mask(i) := complete_mask(i - 1) && complete(rob_addr)
    }
  }

  // set the jmp mask
  val jmp_valid = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  def isJmp(uop: MicroOp): Bool = MuxLookup(uop.fu_code, false.B, Array(
    FU_JMP -> true.B,
    FU_CSR -> (uop.csr_code === CSR_ECALL || uop.csr_code === CSR_MRET)
  ))

  for (i <- 0 until deq_width) {
    val rob_addr = getIdx(next_deq_vec(i))
    val deq = rob.read(rob_addr)
    io.commit(i) := deq
    jmp_valid(i) := isJmp(deq)
    if (i == 0) {
      jmp_mask(i) := true.B
    } else {
      // todo: currently only support 2-way
      jmp_mask(i) := !jmp_valid(0)
    }
    io.commit(i).valid := valid_vec(i) && complete_mask(i) && jmp_mask(i)
  }

  // generate jmp_packet
  val jmp_1h = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  io.jmp_packet := 0.U.asTypeOf(new JmpPacket)
  for (i <- 0 until deq_width) {
    val uop_commit = io.commit(i) 
    jmp_1h(i) := jmp_valid(i) && uop_commit.valid
    when (jmp_1h(i)) {
      io.jmp_packet.valid   := true.B
      io.jmp_packet.inst_pc := uop_commit.pc
      io.jmp_packet.jmp     := uop_commit.real_br
      io.jmp_packet.jmp_pc  := uop_commit.real_bpc
      io.jmp_packet.mis     := Mux(uop_commit.real_br, 
                                   (uop_commit.pred_br && (uop_commit.real_bpc =/= uop_commit.pred_bpc)) || !uop_commit.pred_br,
                                   uop_commit.pred_br)
    }
  }

  /* --------------- flush --------------- */

  when (io.flush) {
    enq_ready := true.B
    complete := VecInit(Seq.fill(RobSize)(false.B))
    enq_vec := VecInit((0 until enq_width).map(_.U(addr_width.W)))
    deq_vec := VecInit((0 until deq_width).map(_.U(addr_width.W)))
  }

}
