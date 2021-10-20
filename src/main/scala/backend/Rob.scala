/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._
import zhoushan.RasConstant._
import difftest._

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
    val cm_mmio = Vec(deq_width, Output(Bool()))
    val jmp_packet = Output(new JmpPacket)
    val sq_deq_req = Output(Bool())
    // flush input
    val flush = Input(Bool())
    // sys instruction ready to issue signal
    val sys_ready = Output(Bool())
  })

  val cm = Wire(Vec(deq_width, new MicroOp))
  val cm_rd_data = Wire(Vec(deq_width, UInt(64.W)))
  val cm_mmio = Wire(Vec(deq_width, Bool()))
  val sq_deq_req = Wire(Bool())

  val rob = SyncReadMem(entries, new MicroOp, SyncReadMem.WriteFirst)

  val enq_vec = RegInit(VecInit((0 until enq_width).map(_.U(addr_width.W))))
  val deq_vec = RegInit(VecInit((0 until deq_width).map(_.U(addr_width.W))))
  val enq_ptr = getIdx(enq_vec(0))
  val deq_ptr = getIdx(deq_vec(0))
  val enq_flag = getFlag(enq_vec(0))
  val deq_flag = getFlag(deq_vec(0))

  val count = Mux(enq_flag === deq_flag, enq_ptr - deq_ptr, entries.U + enq_ptr - deq_ptr)
  val rob_empty = (enq_flag === deq_flag) && (enq_ptr === deq_ptr)

  val num_enq = Mux(io.in.fire(), PopCount(io.in.bits.vec.map(_.valid)), 0.U)
  val num_deq = PopCount(cm.map(_.valid))

  // even though deq_width = 2, we may deq only 1 instruction each time
  val num_try_deq = Mux(count >= 1.U, 1.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = num_after_enq

  // be careful that enq_ready is register, not wire
  val enq_ready = RegInit(true.B)
  enq_ready := (entries - enq_width).U >= next_valid_entry

  // when instructions are executed, update complete & ecp
  // be careful that complete & ecp are regs, remember to sync with SyncReadMem rob
  val complete = RegInit(VecInit(Seq.fill(RobSize)(false.B)))
  val ecp = RegInit(VecInit(Seq.fill(RobSize)(0.U.asTypeOf(new ExCommitPacket))))

  /* --------------- enq ----------------- */

  val offset = Wire(Vec(enq_width, UInt(log2Up(enq_width).W)))
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

    val enq_idx = getIdx(enq_vec(offset(i)))

    when (io.in.bits.vec(i).valid && io.in.fire() && !io.flush) {
      rob.write(enq_idx, enq)          // write to rob
      complete(enq_idx) := false.B     // mark as not completed
      ecp(enq_idx) := 0.U.asTypeOf(new ExCommitPacket)
      io.rob_addr(i) := enq_idx
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

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count, deq_width) - 1.U)
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  // set the complete mask
  // be careful of async read complete
  val complete_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  for (i <- 0 until deq_width) {
    val complete_addr_async = getIdx(deq_vec(i))
    if (i == 0) {
      complete_mask(i) := complete(complete_addr_async)
    } else {
      complete_mask(i) := complete_mask(i - 1) && complete(complete_addr_async)
    }
  }

  // set the jmp mask, store mask & deq, generate jmp_packet for IF & deq_req for SQ
  val jmp_valid = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_mis = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val jmp_1h = WireInit(VecInit(Seq.fill(deq_width)(false.B)))

  io.jmp_packet := 0.U.asTypeOf(new JmpPacket)

  val store_valid = WireInit(VecInit(Seq.fill(deq_width)(false.B)))
  val store_mask = WireInit(VecInit(Seq.fill(deq_width)(false.B)))

  sq_deq_req := false.B

  // SYS instruction ready to issue signal
  io.sys_ready := false.B
  val sys_in_flight = RegInit(false.B)

  // dep uop (async) & ecp (sync)
  val deq_idx_sync = Wire(Vec(deq_width, UInt(idx_width.W)))
  val deq_uop = Wire(Vec(deq_width, new MicroOp))
  val deq_idx_async = Wire(Vec(deq_width, UInt(idx_width.W)))
  val deq_ecp = Wire(Vec(deq_width, new ExCommitPacket))

  // CSR registers from/to CSR unit
  val csr_mstatus       = WireInit(UInt(64.W), "h00001800".U)
  val csr_mie_mtie      = WireInit(Bool(), false.B)
  val csr_mtvec_idx     = WireInit(UInt(30.W), 0.U)
  val csr_mip_mtip_intr = WireInit(Bool(), false.B)

  BoringUtils.addSink(csr_mstatus, "csr_mstatus")
  BoringUtils.addSink(csr_mie_mtie, "csr_mie_mtie")
  BoringUtils.addSink(csr_mtvec_idx, "csr_mtvec_idx")
  BoringUtils.addSink(csr_mip_mtip_intr, "csr_mip_mtip_intr")

  val intr         = WireInit(Bool(), false.B)
  val intr_mstatus = WireInit(UInt(64.W), "h00001800".U)
  val intr_mepc    = WireInit(UInt(64.W), 0.U)
  val intr_mcause  = WireInit(UInt(64.W), 0.U)

  BoringUtils.addSource(intr, "intr")
  BoringUtils.addSource(intr_mstatus, "intr_mstatus")
  BoringUtils.addSource(intr_mepc, "intr_mepc")
  BoringUtils.addSource(intr_mcause, "intr_mcause")

  // interrupt
  val s_intr_idle :: s_intr_wait :: Nil = Enum(2)
  val intr_state = RegInit(s_intr_idle)

  val intr_jmp_pc = WireInit(UInt(32.W), 0.U)

  val intr_global_en = (csr_mstatus(3) === 1.U)
  val intr_clint_en = csr_mie_mtie

  switch (intr_state) {
    is (s_intr_idle) {
      when (intr_global_en && intr_clint_en) {
        intr_state := s_intr_wait
      }
    }
    is (s_intr_wait) {
      when (intr_global_en && intr_clint_en) {
        when (cm(0).valid && csr_mip_mtip_intr && !sys_in_flight) {
          intr_mstatus := Cat(csr_mstatus(63, 8), csr_mstatus(3), csr_mstatus(6, 4), 0.U, csr_mstatus(2, 0))
          intr_mepc := cm(0).pc
          intr_mcause := "h8000000000000007".U
          intr := true.B
          intr_jmp_pc := Cat(csr_mtvec_idx, Fill(2, 0.U))
          intr_state := s_intr_idle
        }
      } .otherwise {
        intr_state := s_intr_idle
      }
    }
  }

  // difftest for arch event
  if (EnableDifftest) {
    val dt_ae = Module(new DifftestArchEvent)
    dt_ae.io.clock        := clock
    dt_ae.io.coreid       := 0.U
    dt_ae.io.intrNO       := RegNext(Mux(intr, intr_mcause, 0.U))
    dt_ae.io.cause        := 0.U
    dt_ae.io.exceptionPC  := RegNext(Mux(intr, intr_mepc, 0.U))
    if (DebugArchEvent) {
      when (dt_ae.io.intrNO =/= 0.U) {
        printf("%d: [DT-AE] intrNO=%x ePC=%x\n", DebugTimer(), dt_ae.io.intrNO, dt_ae.io.exceptionPC)
      }
    }
  }

  for (i <- 0 until deq_width) {
    deq_idx_sync(i) := getIdx(next_deq_vec(i))
    deq_uop(i) := rob.read(deq_idx_sync(i))
    deq_idx_async(i) := getIdx(deq_vec(i))
    deq_ecp(i) := ecp(deq_idx_async(i))
    if (i == 0) {
      when (deq_uop(i).fu_code === s"b$FU_SYS".U && !sys_in_flight && !rob_empty) {
        io.sys_ready := true.B
        sys_in_flight := true.B
      }
    }
    cm(i) := deq_uop(i)
    cm_rd_data(i) := deq_ecp(i).rd_data
    cm_mmio(i) := deq_ecp(i).mmio
    jmp_valid(i) := deq_ecp(i).jmp_valid
    jmp_mis(i) := deq_ecp(i).mis
    store_valid(i) := deq_ecp(i).store_valid
    if (i == 0) {
      jmp_mask(i) := true.B
      store_mask(i) := true.B
    } else {
      // todo: currently only support 2-way commit
      // if branch instr 0 is commited, we allow instr 1 to be commited only
      //   when 1) instr 0 not mis-predict, and 2) instr 1 is not branch instr
      jmp_mask(i) := Mux(jmp_valid(0), !jmp_mis(0) && !jmp_valid(1), true.B)
      store_mask(i) := Mux(store_valid(0), !store_valid(1), true.B)
    }
    // resolve WAW dependency
    // don't commit two instructions with same rd_addr at the same time
    if (i == 0) {
      cm(i).valid := valid_vec(i) && complete_mask(i) && jmp_mask(i) && store_mask(i)
    } else {
      // todo: currently only support 2-way commit
      cm(i).valid := valid_vec(i) && complete_mask(i) && jmp_mask(i) && store_mask(i) &&
                        Mux(cm(0).valid && cm(0).rd_en && cm(1).rd_en, cm(0).rd_addr =/= cm(1).rd_addr, true.B)
    }

    // update sys_in_flight status register
    when (cm(i).valid && sys_in_flight) {
      sys_in_flight := false.B
    }

    // update sq_deq_req
    when (cm(i).valid && store_valid(i)) {
      sq_deq_req := true.B
    }

    // update jmp_packet
    jmp_1h(i) := jmp_valid(i) && cm(i).valid
    when (jmp_1h(i)) {
      io.jmp_packet.valid   := true.B
      io.jmp_packet.inst_pc := deq_uop(i).pc
      io.jmp_packet.jmp     := deq_ecp(i).jmp
      io.jmp_packet.jmp_pc  := deq_ecp(i).jmp_pc
      io.jmp_packet.mis     := deq_ecp(i).mis
      io.jmp_packet.sys     := (deq_uop(i).fu_code === s"b$FU_SYS".U) &&
                               (deq_uop(i).sys_code === s"b$SYS_FENCEI".U)

      // debug info
      io.jmp_packet.pred_br := deq_uop(i).pred_br
      io.jmp_packet.pred_bpc := deq_uop(i).pred_bpc

      // ref: riscv-spec-20191213 page 21-22
      val rd_link = (deq_uop(i).rd_addr === 1.U || deq_uop(i).rd_addr === 5.U)
      val rs1_link = (deq_uop(i).rs1_addr === 1.U || deq_uop(i).rs1_addr === 5.U)
      val ras_type = WireInit(RAS_X)
      when (deq_uop(i).jmp_code === s"b$JMP_JAL".U) {
        when (rd_link) {
          ras_type := RAS_PUSH
        }
      }
      when (deq_uop(i).jmp_code === s"b$JMP_JALR".U) {
        ras_type := MuxLookup(Cat(rd_link.asUInt(), rs1_link.asUInt()), RAS_X, Array(
          "b00".U -> RAS_X,
          "b01".U -> RAS_POP,
          "b10".U -> RAS_PUSH,
          "b11".U -> Mux(deq_uop(i).rd_addr === deq_uop(i).rs1_addr, RAS_PUSH, RAS_POP_THEN_PUSH)
        ))
      }
      io.jmp_packet.ras_type := ras_type
    }
  }

  // update jmp_packet for interrupt
  when (intr) {
    io.jmp_packet.valid   := true.B
    io.jmp_packet.inst_pc := intr_mepc(31, 0)
    io.jmp_packet.jmp     := true.B
    io.jmp_packet.jmp_pc  := intr_jmp_pc
    io.jmp_packet.mis     := true.B
    io.jmp_packet.sys     := true.B
    io.jmp_packet.pred_br := false.B
    io.jmp_packet.pred_bpc := 0.U
  }

  if (DebugJmpPacket) {
    when (io.jmp_packet.valid) {
      printf("%d: [ JMP ] pc=%x pred=%x->%x real=%x->%x mis=%x sys=%x\n", DebugTimer(),
             io.jmp_packet.inst_pc, io.jmp_packet.pred_br, io.jmp_packet.pred_bpc,
             io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.mis, io.jmp_packet.sys)
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

  /* --------------- reset --------------- */

  when (reset.asBool()) {
    for (i <- 0 until entries) {
      rob.write(i.U, 0.U.asTypeOf(new MicroOp))
    }
  }

  /* --------------- output -------------- */

  io.cm := cm
  for (i <- 0 until deq_width) {
    io.cm(i).valid := cm(i).valid && !intr
  }
  io.cm_rd_data := cm_rd_data
  io.cm_mmio := cm_mmio
  io.sq_deq_req := sq_deq_req && !intr

  /* --------------- debug --------------- */

  if (EnableDifftest && EnableMisRateCounter) {
    val jmp_counter = RegInit(UInt(64.W), 0.U)
    val mis_counter = RegInit(UInt(64.W), 0.U)
    when (io.jmp_packet.valid) {
      jmp_counter := jmp_counter + 1.U
      when (io.jmp_packet.mis) {
        mis_counter := mis_counter + 1.U
      }
    }
    BoringUtils.addSource(jmp_counter, "profile_jmp_counter")
    BoringUtils.addSource(mis_counter, "profile_mis_counter")
  }

  if (EnableDifftest && EnableQueueAnalyzer) {
    val queue_rob_count = count
    BoringUtils.addSource(queue_rob_count, "profile_queue_rob_count")
  }

}
