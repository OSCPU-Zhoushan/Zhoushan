package zhoushan

import chisel3._
import chisel3.util._

class StoreQueueEntry extends Bundle with AxiParameters {
  val addr = UInt(AxiAddrWidth.W)
  val wdata = UInt(AxiDataWidth.W)
  val wmask = UInt((AxiDataWidth / 8).W)
  val valid = Bool()
}

class StoreQueue extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    // flush request from ROB
    val flush = Input(Bool())
    // from EX stage - LSU
    val in = Flipped(new CacheBusIO)
    // to data cache
    val out_st = new CacheBusIO   // id = ZhoushanConfig.SqStoreId
    val out_ld = new CacheBusIO   // id = ZhoushanConfig.SqLoadId
    // deq request from ROB
    val deq_req = Input(Bool())
  })

  val sq = Mem(StoreQueueSize, new StoreQueueEntry)
  val enq_ptr = Counter(StoreQueueSize)
  val deq_ptr = Counter(StoreQueueSize)
  val maybe_full = RegInit(false.B)
  val empty = (enq_ptr.value === deq_ptr.value) && !maybe_full
  val full = (enq_ptr.value === deq_ptr.value) && maybe_full

  /* ---------- Store Logic ---------- */

  // dequeue
  val deq_idle :: deq_wait :: Nil = Enum(2)
  val deq_state = RegInit(deq_idle)

  val deq_req_counter = RegInit(UInt((log2Up(StoreQueueSize) + 1).W), 0.U)
  val deq_req_empty = (deq_req_counter === 0.U)

  val deq_fire = WireInit(false.B)
  val deq_valid = !empty && !deq_req_empty
  val deq_ready = io.out_st.req.ready && (deq_state === deq_idle)
  deq_fire := deq_valid && deq_ready

  when (io.deq_req && !deq_fire) {
    deq_req_counter := deq_req_counter + 1.U
  } .elsewhen (!io.deq_req && deq_fire) {
    deq_req_counter := deq_req_counter - 1.U
  }

  when (deq_fire) {
    sq(deq_ptr.value).valid := false.B
    deq_ptr.inc()

    if (DebugStoreQueue) {
      printf("%d: [SQ - D] idx=%d v=%x addr=%x wdata=%x wmask=%x\n", DebugTimer(), deq_ptr.value,
             sq(deq_ptr.value).valid, sq(deq_ptr.value).addr, sq(deq_ptr.value).wdata, sq(deq_ptr.value).wmask)
    }
  }

  switch (deq_state) {
    is (deq_idle) {
      when (deq_fire) {
        deq_state := deq_wait
      }
    }
    is (deq_wait) {
      when (io.out_st.resp.fire()) {
        deq_state := deq_idle
      }
    }
  }

  // enqueue (be careful that enqueue is done after dequeue)
  val enq_idle :: enq_wait :: Nil = Enum(2)
  val enq_state = RegInit(enq_idle)

  val enq_valid = io.in.req.valid && io.in.req.bits.wen
  val enq_ready = (!full || deq_fire) && (enq_state === enq_idle)
  val enq_fire = enq_valid && enq_ready

  when (enq_fire) {
    val enq = Wire(new StoreQueueEntry)
    enq.addr := io.in.req.bits.addr
    enq.wdata := io.in.req.bits.wdata
    enq.wmask := io.in.req.bits.wmask
    enq.valid := true.B
    sq(enq_ptr.value) := enq
    enq_ptr.inc()

    if (DebugStoreQueue) {
      printf("%d: [SQ - E] idx=%d v=%x addr=%x wdata=%x wmask=%x\n", DebugTimer(), enq_ptr.value,
             enq.valid, enq.addr, enq.wdata, enq.wmask)
    }
  }

  switch (enq_state) {
    is (enq_idle) {
      when (enq_fire) {
        enq_state := enq_wait
      }
    }
    is (enq_wait) {
      when (io.in.resp.fire()) {
        enq_state := enq_idle
      }
    }
  }

  when (enq_fire =/= deq_fire) {
    maybe_full := enq_fire
  }

  /* ---------- Load Logic ----------- */

  val load_addr_match = WireInit(VecInit(Seq.fill(StoreQueueSize)(false.B)))

  for (i <- 0 until StoreQueueSize) {
    load_addr_match(i) := sq(i).valid && (sq(i).addr === io.in.req.bits.addr)
  }

  val load_hit = Cat(load_addr_match.reverse).orR
  // todo: optimize the load logic

  /* ---------- Flush ---------------- */

  val flush_idle :: flush_wait :: Nil = Enum(2)
  val flush_state = RegInit(flush_idle)

  def flush_all() = {
    enq_ptr.reset()
    deq_ptr.reset()
    maybe_full := false.B
    for (i <- 0 until StoreQueueSize) {
      sq(i).valid := false.B
    }
  }

  switch (flush_state) {
    is (flush_idle) {
      // no flush signal
      when (io.flush) {
        when (deq_req_empty) {
          flush_all()
          if (DebugStoreQueue) {
            printf("%d: [SQ - F] SQ empty - OK\n", DebugTimer())
          }
        } .otherwise {
          flush_state := flush_wait
          if (DebugStoreQueue) {
            printf("%d: [SQ - F] SQ not empty - deq_req_counter=%d\n", DebugTimer(), deq_req_counter)
          }
        }
      }
    }
    is (flush_wait) {
      // flush signal, handle remaining deq request
      when (deq_req_empty) {
        flush_all()
        flush_state := flush_idle
        if (DebugStoreQueue) {
          printf("%d: [SQ - F] SQ empty - OK, clear all deq req\n", DebugTimer())
        }
      }
    }
  }

  /* ----- Cachebus Handshake -------- */

  val is_store = io.in.req.valid && io.in.req.bits.wen
  val reg_is_store = RegInit(false.B)
  val is_store_real = Mux(io.in.req.valid, is_store, reg_is_store)

  io.in.req.ready := false.B
  when (io.in.req.valid) {
    when (io.in.req.bits.wen) {
      io.in.req.ready := enq_ready && (flush_state === flush_idle)
      reg_is_store := true.B
    }
    when (io.in.req.bits.ren) {
      io.in.req.ready := io.out_ld.req.ready && !load_hit && (flush_state === flush_idle)
      reg_is_store := false.B
    }
  }

  io.in.resp.valid      := Mux(is_store_real, (enq_state === enq_wait), io.out_ld.resp.valid)
  io.in.resp.bits.rdata := io.out_ld.resp.bits.rdata
  io.in.resp.bits.user  := 0.U
  io.in.resp.bits.id    := 0.U

  io.out_st.req.valid      := deq_valid
  io.out_st.req.bits.addr  := sq(deq_ptr.value).addr
  io.out_st.req.bits.wdata := sq(deq_ptr.value).wdata
  io.out_st.req.bits.wmask := sq(deq_ptr.value).wmask
  io.out_st.req.bits.ren   := false.B
  io.out_st.req.bits.wen   := true.B
  io.out_st.req.bits.user  := 0.U
  io.out_st.req.bits.id    := SqStoreId.U

  io.out_st.resp.ready     := (deq_state === deq_wait)

  io.out_ld.req.valid      := io.in.req.valid && io.in.req.bits.ren && !load_hit && (flush_state === flush_idle)
  io.out_ld.req.bits.addr  := io.in.req.bits.addr
  io.out_ld.req.bits.wdata := 0.U
  io.out_ld.req.bits.wmask := 0.U
  io.out_ld.req.bits.ren   := true.B
  io.out_ld.req.bits.wen   := false.B
  io.out_ld.req.bits.user  := 0.U
  io.out_ld.req.bits.id    := SqLoadId.U

  io.out_ld.resp.ready     := io.in.resp.ready

}
