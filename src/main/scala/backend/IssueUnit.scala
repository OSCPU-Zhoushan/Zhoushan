package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class MicroOpVec(vec_width: Int) extends Bundle {
  val vec = Vec(vec_width, Output(new MicroOp))
}

class IssueUnit extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    // input
    val in = Flipped(Decoupled(new MicroOpVec(DecodeWidth)))
    val rob_addr = Vec(DecodeWidth, Input(UInt(log2Up(RobSize).W)))
    // output
    val out = Vec(IssueWidth, Output(new MicroOp))
    // from rename stage
    val avail_list = Input(UInt(PrfSize.W))
    // from ex stage
    val lsu_ready = Input(Bool())
  })

  val int_iq = Module(new IntIssueQueueOutOfOrder(entries = IntIssueQueueSize, enq_width = DecodeWidth, deq_width = IssueWidth - 1))
  int_iq.io.flush := io.flush
  int_iq.io.rob_addr := io.rob_addr
  int_iq.io.avail_list := io.avail_list
  int_iq.io.fu_ready := true.B

  val mem_iq = Module(new IssueQueueInOrder(entries = MemIssueQueueSize, enq_width = DecodeWidth, deq_width = 1))
  mem_iq.io.flush := io.flush
  mem_iq.io.rob_addr := io.rob_addr
  mem_iq.io.avail_list := io.avail_list
  mem_iq.io.fu_ready := io.lsu_ready

  // routing network
  val uop_int = WireInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))
  val uop_mem = WireInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))

  uop_int := io.in.bits.vec
  uop_mem := io.in.bits.vec

  for (i <- 0 until DecodeWidth) {
    when (uop_int(i).fu_code === FU_MEM) {  // todo: modify this in the future
      uop_int(i).valid := false.B
    }
    when (uop_mem(i).fu_code =/= FU_MEM) {
      uop_mem(i).valid := false.B
    }
  }

  int_iq.io.in.bits.vec := uop_int
  int_iq.io.in.valid := io.in.valid && Cat(uop_int.map(_.valid)).orR && mem_iq.io.in.ready
  mem_iq.io.in.bits.vec := uop_mem
  mem_iq.io.in.valid := io.in.valid && Cat(uop_mem.map(_.valid)).orR && int_iq.io.in.ready

  for (i <- 0 until IssueWidth - 1) {
    io.out(i) := int_iq.io.out(i)
  }
  io.out(IssueWidth - 1) := mem_iq.io.out(0)

  io.in.ready := int_iq.io.in.ready && mem_iq.io.in.ready

}

abstract class AbstractIssueQueue(entries: Int, enq_width: Int, deq_width: Int) extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in = Flipped(Decoupled(new MicroOpVec(enq_width)))
    val rob_addr = Vec(enq_width, Input(UInt(log2Up(RobSize).W)))
    val out = Vec(deq_width, Output(new MicroOp))
    // from rename stage
    val avail_list = Input(UInt(PrfSize.W))
    // from ex stage
    val fu_ready = Input(Bool())
  })
}

class IntIssueQueueOutOfOrder(entries: Int, enq_width: Int, deq_width: Int) extends AbstractIssueQueue(entries, enq_width, deq_width) {
  val idx_width = log2Up(entries)
  val addr_width = idx_width + 1
  def getIdx(x: UInt): UInt = x(idx_width - 1, 0)

  val buf = Mem(entries, new MicroOp)

  val enq_vec = RegInit(VecInit((0 until enq_width).map(_.U(addr_width.W))))
  val enq_ptr = enq_vec(0)

  val num_enq = Mux(io.in.fire(), PopCount(io.in.bits.vec.map(_.valid)), 0.U)
  val num_deq = PopCount(io.out.map(_.valid))

  val enq_ready = WireInit(true.B)
  enq_ready := enq_ptr <= (entries - enq_width).U(addr_width.W)

  // deq

  val deq_vec = Wire(Vec(deq_width, UInt(idx_width.W)))
  val deq_vec_valid = Wire(Vec(deq_width, Bool()))

  // ready to issue check
  val ready_list = WireInit(VecInit(Seq.fill(entries)(false.B)))
  for (i <- 0 until entries) {
    val rs1_avail = io.avail_list(buf(i).rs1_paddr)
    val rs2_avail = io.avail_list(buf(i).rs2_paddr)
    val fu_ready = io.fu_ready
    ready_list(i) := rs1_avail && rs2_avail && fu_ready
  }

  // todo: currently only support 2-way
  val rl0 = Cat(ready_list.reverse)
  deq_vec(0) := PriorityEncoder(rl0)
  deq_vec_valid(0) := ready_list(deq_vec(0))

  val rl1 = rl0 & ~UIntToOH(deq_vec(0), entries)
  deq_vec(1) := PriorityEncoder(rl1)
  deq_vec_valid(1) := ready_list(deq_vec(1)) && (deq_vec(1) =/= deq_vec(0))

  for (i <- 0 until deq_width) {
    val deq = buf(deq_vec(i))
    io.out(i) := deq
    io.out(i).valid := deq.valid && deq_vec_valid(i)
  }

  // collapse logic
  // todo: currently only support 2-way
  val up1 = WireInit(VecInit(Seq.fill(entries)(false.B)))
  val up2 = WireInit(VecInit(Seq.fill(entries)(false.B)))

  for (i <- 0 until entries) {
    up1(i) := (i.U >= deq_vec(0)) && deq_vec_valid(0) && !up2(i)
  }
  for (i <- 0 until entries) {
    up2(i) := (i.U >= deq_vec(1) - 1.U) && deq_vec_valid(1)
  }
  for (i <- 0 until entries) {
    when (up1(i)) {
      if (i < entries - 1) {
        buf(i.U) := buf((i + 1).U)
      } else {
        buf(i.U) := 0.U.asTypeOf(new MicroOp)
      }
    }
    when (up2(i)) {
      if (i < entries - 2) {
        buf(i.U) := buf((i + 2).U)
      } else {
        buf(i.U) := 0.U.asTypeOf(new MicroOp)
      }
    }
  }

  // enq

  val enq_offset = Wire(Vec(enq_width, UInt(log2Ceil(enq_width + 1).W)))
  for (i <- 0 until enq_width) {
    if (i == 0) {
      enq_offset(i) := 0.U
    } else {
      // todo: currently only support 2-way
      enq_offset(i) := PopCount(io.in.bits.vec(0).valid)
    }
  }

  for (i <- 0 until enq_width) {
    val enq = Wire(new MicroOp)
    enq := io.in.bits.vec(i)
    enq.rob_addr := io.rob_addr(i)

    when (enq.valid && io.in.fire() && !io.flush) {
      buf(getIdx(enq_vec(enq_offset(i)))) := enq
    }
  }

  val next_enq_vec = VecInit(enq_vec.map(_ + num_enq - num_deq))

  when ((io.in.fire() || Cat(io.out.map(_.valid)).orR) && !io.flush) {
    enq_vec := next_enq_vec
  }

  io.in.ready := enq_ready

  // flush

  when (reset.asBool() || io.flush) {
    for (i <- 0 until entries) {
      buf(i) := 0.U.asTypeOf(new MicroOp)
    }
    enq_ready := true.B
    enq_vec := VecInit((0 until enq_width).map(_.U(addr_width.W)))
  }

  if (DebugIntIssueQueue) {
    for (i <- 0 until entries / 8) {
      printf("%d: [IQ - I] ", DebugTimer());
      for (j <- 0 until 8) {
        val idx = i * 8 + j
        printf("%d-%x(%x)\t", idx.U, buf(idx).pc, buf(idx).valid)
      }
      printf("\n")
    }
  }
}

class IssueQueueInOrder(entries: Int, enq_width: Int, deq_width: Int) extends AbstractIssueQueue(entries, enq_width, deq_width) {
  val idx_width = log2Up(entries)
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
  val num_deq = PopCount(io.out.map(_.valid))

  // even though deq_width = IssueWidth, we may deq only 1 instruction each time
  val num_try_deq = Mux(count >= 1.U, 1.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = num_after_enq

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
    enq.rob_addr := io.rob_addr(i)

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

  // ready to issue check
  val issue_valid = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  for (i <- 0 until deq_width) {
    val rs1_avail = io.avail_list(io.out(i).rs1_paddr)
    val rs2_avail = io.avail_list(io.out(i).rs2_paddr)
    val fu_ready = io.fu_ready
    if (i == 0) {
      issue_valid(i) := rs1_avail && rs2_avail && fu_ready
    } else {
      issue_valid(i) := issue_valid(i - 1) && rs1_avail && rs2_avail && fu_ready
    }
  }

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count)(deq_width - 1, 0) - 1.U)
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  for (i <- 0 until deq_width) {
    val deq = buf.read(getIdx(next_deq_vec(i)))
    io.out(i) := deq
    io.out(i).valid := deq.valid && valid_vec(i) && issue_valid(i)
  }

  // flush

  when (io.flush) {
    enq_ready := true.B
    enq_vec := VecInit((0 until enq_width).map(_.U(addr_width.W)))
    deq_vec := VecInit((0 until deq_width).map(_.U(addr_width.W)))
  }

}
