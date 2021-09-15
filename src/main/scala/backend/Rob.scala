package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._
import zhoushan.RasConstant._

class Rob extends Module with ZhoushanConfig {
  val entries = RobSize
  val enq_width = DecodeWidth
  val deq_width = CommitWidth

  val io = IO(new Bundle {
    // from dispatch stage
    val in = Flipped(Decoupled(new MicroOpVec(enq_width)))
    val out = Decoupled(new MicroOpVec(enq_width))
    // from execution --> commit stage
    val exe = Vec(IssueWidth, Input(new MicroOp))
    val exe_jcp = Vec(IssueWidth - 1, Input(new JmpCommitPacket))
    // commit stage
    val cm = Vec(CommitWidth, Output(new MicroOp))
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
  val num_deq = Mux(io.out.fire(), PopCount(io.out.bits.vec.map(_.valid)), 0.U)

  // even though deq_width = 2, we may deq only 1 instruction each time
  val num_try_deq = Mux(count >= 1.U, 1.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = num_after_enq - num_try_deq

  enq_ready := (entries - enq_width).U >= next_valid_entry && io.out.ready

  // when instructions are executed, update complete & jcp
  val complete = RegInit(VecInit(Seq.fill(RobSize)(false.B)))
  val jcp = RegInit(VecInit(Seq.fill(RobSize)(0.U.asTypeOf(new JmpCommitPacket))))

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

    val enq_addr = getIdx(enq_vec(offset(i)))
    val enq_out = RegInit(0.U.asTypeOf(new MicroOp))

    when (io.in.bits.vec(i).valid && io.in.fire() && !io.flush) {
      rob.write(enq_addr, enq)          // write to rob
      complete(enq_addr) := false.B     // mark as not completed
      jcp(enq_addr).valid := false.B
      enq_out := io.in.bits.vec(i)      // pass input to output
      enq_out.rob_addr := enq_addr
    } .otherwise {
      enq_out := 0.U.asTypeOf(new MicroOp)
    }

    io.out.bits.vec(i) := enq_out
  }

  val next_enq_vec = VecInit(enq_vec.map(_ + num_enq))

  when (io.in.fire() && !io.flush) {
    enq_vec := next_enq_vec
  }

  io.in.ready := enq_ready
  io.out.valid := io.in.valid

  /* --------------- complete ------------ */

  for (i <- 0 until IssueWidth) {
    val rob_addr = io.exe(i).rob_addr
    complete(rob_addr) := io.exe(i).valid
    if (i < IssueWidth - 1) {
      jcp(rob_addr) := io.exe_jcp(i)
    }
  }

  /* --------------- deq ----------------- */

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count)(deq_width - 1, 0) - 1.U)
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  // set the complete mask
  val complete_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  for (i <- 0 until deq_width) {
    val addr = getIdx(next_deq_vec(i))
    if (i == 0) {
      complete_mask(i) := complete(addr)
    } else {
      complete_mask(i) := complete_mask(i - 1) && complete(addr)
    }
  }

  // set the jmp mask & deq, generate jmp_packet
  val jmp_valid = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_1h = WireInit(VecInit(Seq.fill(deq_width)(false.B)))

  io.jmp_packet := 0.U.asTypeOf(new JmpPacket)

  for (i <- 0 until deq_width) {
    val deq_addr = getIdx(next_deq_vec(i))
    val deq = rob.read(deq_addr)
    io.cm(i) := deq
    jmp_valid(i) := jcp(deq_addr).valid
    if (i == 0) {
      jmp_mask(i) := true.B
    } else {
      // todo: currently only support 2-way commit
      jmp_mask(i) := !jmp_valid(0)
    }
    io.cm(i).valid := valid_vec(i) && complete_mask(i) && jmp_mask(i)

    // update jmp_packet
    jmp_1h(i) := jmp_valid(i) && deq.valid
    when (jmp_1h(i)) {
      io.jmp_packet.valid   := true.B
      io.jmp_packet.inst_pc := deq.pc
      io.jmp_packet.jmp     := jcp(deq_addr).jmp
      io.jmp_packet.jmp_pc  := jcp(deq_addr).jmp_pc
      io.jmp_packet.mis     := Mux(io.jmp_packet.jmp, 
                                   (deq.pred_br && (io.jmp_packet.jmp_pc =/= deq.pred_bpc)) || !deq.pred_br,
                                   deq.pred_br)
      io.jmp_packet.intr    := false.B  // todo

      // ref: riscv-spec-20191213 page 21-22
      val rd_link = (deq.rd_addr === 1.U || deq.rd_addr === 5.U)
      val rs1_link = (deq.rs1_addr === 1.U || deq.rs1_addr === 5.U)
      val ras_type = WireInit(RAS_X)
      when (deq.jmp_code === JMP_JAL) {
        when (rd_link) {
          ras_type := RAS_PUSH
        }
      }
      when (deq.jmp_code === JMP_JALR) {
        ras_type := MuxLookup(Cat(rd_link.asUInt(), rs1_link.asUInt()), RAS_X, Array(
          "b00".U -> RAS_X,
          "b01".U -> RAS_POP,
          "b10".U -> RAS_PUSH,
          "b11".U -> Mux(deq.rd_addr === deq.rs1_addr, RAS_PUSH, RAS_POP_THEN_PUSH)
        ))
      }
      io.jmp_packet.ras_type := ras_type
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
