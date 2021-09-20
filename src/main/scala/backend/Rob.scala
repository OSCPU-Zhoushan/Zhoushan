package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._
import zhoushan.RasConstant._

class Rob extends Module with ZhoushanConfig {
  val entries = RobSize
  val enq_width = DecodeWidth
  val deq_width = CommitWidth

  val idx_width = log2Up(entries)
  val addr_width = idx_width + 1  // MSB is flag bit
  def getIdx(x: UInt): UInt = x(idx_width - 1, 0)
  def getFlag(x: UInt): Bool = x(addr_width - 1).asBool()

  val io = IO(new Bundle {
    // input
    val in = Flipped(Decoupled(new MicroOpVec(enq_width)))
    val rob_addr = Vec(enq_width, Output(UInt(idx_width.W)))
    // from execution --> commit stage
    val exe = Vec(IssueWidth, Input(new MicroOp))
    val exe_ecp = Vec(IssueWidth, Input(new ExCommitPacket))
    // commit stage
    val cm = Vec(deq_width, Output(new MicroOp))
    val cm_rd_data = Vec(deq_width, Output(UInt(64.W)))
    val jmp_packet = Output(new JmpPacket)
    val sq_deq_req = Output(Bool())
    val flush = Input(Bool())
  })

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
  val num_deq = PopCount(io.cm.map(_.valid))

  // even though deq_width = 2, we may deq only 1 instruction each time
  val num_try_deq = Mux(count >= 1.U, 1.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = num_after_enq

  // be careful that enq_ready is register, not wire
  enq_ready := (entries - enq_width).U >= next_valid_entry

  // when instructions are executed, update complete & ecp
  // be careful that complete & ecp are regs, remember to sync with SyncReadMem rob
  val complete = RegInit(VecInit(Seq.fill(RobSize)(false.B)))
  val ecp = RegInit(VecInit(Seq.fill(RobSize)(0.U.asTypeOf(new ExCommitPacket))))

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

    when (io.in.bits.vec(i).valid && io.in.fire() && !io.flush) {
      rob.write(enq_addr, enq)          // write to rob
      complete(enq_addr) := false.B     // mark as not completed
      ecp(enq_addr) := 0.U.asTypeOf(new ExCommitPacket)
      io.rob_addr(i) := enq_addr
    } .otherwise {
      io.rob_addr(i) := 0.U
    }
  }

  val next_enq_vec = VecInit(enq_vec.map(_ + num_enq))

  when (io.in.fire() && !io.flush) {
    enq_vec := next_enq_vec
  }

  io.in.ready := enq_ready

  /* --------------- complete ------------ */

  for (i <- 0 until IssueWidth) {
    val rob_addr = io.exe(i).rob_addr
    when (io.exe(i).valid) {
      complete(rob_addr) := true.B
      ecp(rob_addr) := io.exe_ecp(i)
    }
  }

  /* --------------- deq ----------------- */

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count)(deq_width - 1, 0) - 1.U)
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  // set the complete mask
  // be careful of async read complete
  val complete_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  for (i <- 0 until deq_width) {
    val deq_addr_async = getIdx(deq_vec(i))
    if (i == 0) {
      complete_mask(i) := complete(deq_addr_async)
    } else {
      complete_mask(i) := complete_mask(i - 1) && complete(deq_addr_async)
    }
  }

  // set the jmp mask, store mask & deq, generate jmp_packet for IF & deq_req for SQ
  val jmp_valid = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_1h = WireInit(VecInit(Seq.fill(deq_width)(false.B)))

  io.jmp_packet := 0.U.asTypeOf(new JmpPacket)

  val store_valid = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val store_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))

  io.sq_deq_req := Cat(store_valid.reverse).orR

  for (i <- 0 until deq_width) {
    val deq_addr_sync = getIdx(next_deq_vec(i))
    val deq_uop = rob.read(deq_addr_sync)
    val deq_addr_async = getIdx(deq_vec(i))
    val deq_ecp = ecp(deq_addr_async)
    io.cm(i) := deq_uop
    io.cm_rd_data(i) := deq_ecp.rd_data
    jmp_valid(i) := deq_ecp.jmp_valid
    store_valid(i) := deq_ecp.store_valid
    if (i == 0) {
      jmp_mask(i) := true.B
      store_mask(i) := true.B
    } else {
      // todo: currently only support 2-way commit
      jmp_mask(i) := !jmp_valid(0)
      store_mask(i) := !store_mask(0)
    }
    // resolve WAW dependency
    // don't commit two instructions with same rd_addr at the same time
    if (i == 0) {
      io.cm(i).valid := valid_vec(i) && complete_mask(i) && jmp_mask(i) && store_mask(i)
    } else {
      // todo: currently only support 2-way commit
      io.cm(i).valid := valid_vec(i) && complete_mask(i) && jmp_mask(i) && store_mask(i) &&
                        Mux(io.cm(0).valid && io.cm(0).rd_en && io.cm(1).rd_en, io.cm(0).rd_addr =/= io.cm(1).rd_addr, true.B)
    }

    // update jmp_packet
    jmp_1h(i) := jmp_valid(i) && io.cm(i).valid
    when (jmp_1h(i)) {
      io.jmp_packet.valid   := true.B
      io.jmp_packet.inst_pc := deq_uop.pc
      io.jmp_packet.jmp     := deq_ecp.jmp
      io.jmp_packet.jmp_pc  := deq_ecp.jmp_pc
      io.jmp_packet.mis     := Mux(io.jmp_packet.jmp, 
                                   (deq_uop.pred_br && (io.jmp_packet.jmp_pc =/= deq_uop.pred_bpc)) || !deq_uop.pred_br,
                                   deq_uop.pred_br)
      io.jmp_packet.intr    := false.B  // todo

      // ref: riscv-spec-20191213 page 21-22
      val rd_link = (deq_uop.rd_addr === 1.U || deq_uop.rd_addr === 5.U)
      val rs1_link = (deq_uop.rs1_addr === 1.U || deq_uop.rs1_addr === 5.U)
      val ras_type = WireInit(RAS_X)
      when (deq_uop.jmp_code === JMP_JAL) {
        when (rd_link) {
          ras_type := RAS_PUSH
        }
      }
      when (deq_uop.jmp_code === JMP_JALR) {
        ras_type := MuxLookup(Cat(rd_link.asUInt(), rs1_link.asUInt()), RAS_X, Array(
          "b00".U -> RAS_X,
          "b01".U -> RAS_POP,
          "b10".U -> RAS_PUSH,
          "b11".U -> Mux(deq_uop.rd_addr === deq_uop.rs1_addr, RAS_PUSH, RAS_POP_THEN_PUSH)
        ))
      }
      io.jmp_packet.ras_type := ras_type
    }
  }

  /* --------------- flush --------------- */

  when (io.flush) {
    enq_ready := true.B
    complete := VecInit(Seq.fill(RobSize)(false.B))
    ecp := VecInit(Seq.fill(RobSize)(0.U.asTypeOf(new ExCommitPacket)))
    enq_vec := VecInit((0 until enq_width).map(_.U(addr_width.W)))
    deq_vec := VecInit((0 until deq_width).map(_.U(addr_width.W)))
  }

}
