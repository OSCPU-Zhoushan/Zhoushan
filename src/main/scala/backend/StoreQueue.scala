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
    val flush = Input(Bool())
    val in = Flipped(new CacheBusIO)
    val out = new CacheBusIO
    val deq_req = Input(Bool()) // dequeue request from rob
  })

  val deq_req_counter = RegInit(UInt((log2Up(StoreQueueSize) + 1).W), 0.U)
  when (io.deq_req) {
    deq_req_counter := deq_req_counter + 1.U
  }

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

  val deq_fire = WireInit(false.B)
  val deq_valid = !empty && (deq_req_counter =/= 0.U)
  val deq_ready = io.out.req.ready && (deq_state === deq_idle)
  deq_fire := deq_valid && deq_ready

  when (deq_fire) {
    sq(deq_ptr.value).valid := false.B
    deq_ptr.inc()
    deq_req_counter := deq_req_counter - 1.U

    if (DebugMsgStoreQueue) {
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
      when (io.out.resp.fire()) {
        deq_state := deq_idle
      }
    }
  }

  // enqueue
  val enq_idle :: enq_wait :: Nil = Enum(2)
  val enq_state = RegInit(enq_idle)

  val enq_valid = io.in.req.valid && io.in.req.bits.wen
  val enq_ready = (!full || io.out.req.ready) && (enq_state === enq_idle)
  val enq_fire = enq_valid && enq_ready

  when (enq_fire) {
    val enq = Wire(new StoreQueueEntry)
    enq.addr := io.in.req.bits.addr
    enq.wdata := io.in.req.bits.wdata
    enq.wmask := io.in.req.bits.wmask
    enq.valid := true.B
    sq(enq_ptr.value) := enq
    enq_ptr.inc()

    if (DebugMsgStoreQueue) {
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

  /* ----- Cachebus Handshake -------- */

  val is_store = io.in.req.valid && io.in.req.bits.wen
  val reg_is_store = RegInit(false.B)
  val is_store_real = Mux(io.in.req.valid, is_store, reg_is_store)

  io.in.req.ready := false.B
  when (io.in.req.valid) {
    when (io.in.req.bits.wen) {
      io.in.req.ready := enq_ready
      reg_is_store := true.B
    }
    when (io.in.req.bits.ren) {
      io.in.req.ready := !load_hit && io.out.req.ready
      reg_is_store := false.B
    }
  }

  io.in.resp.valid      := Mux(is_store_real, (enq_state === enq_wait), io.out.resp.valid)
  io.in.resp.bits.rdata := io.out.resp.bits.rdata
  io.in.resp.bits.user  := 0.U

  io.out.req.valid      := Mux(is_store_real, deq_valid, io.in.req.valid)
  io.out.req.bits.addr  := Mux(is_store_real, sq(deq_ptr.value).addr, io.in.req.bits.addr)
  io.out.req.bits.wdata := Mux(is_store_real, sq(deq_ptr.value).wdata, 0.U)
  io.out.req.bits.wmask := Mux(is_store_real, sq(deq_ptr.value).wmask, 0.U)
  io.out.req.bits.ren   := !is_store_real
  io.out.req.bits.wen   := is_store_real
  io.out.req.bits.user  := 0.U

  io.out.resp.ready     := Mux(is_store_real, (deq_state === deq_wait), io.in.resp.ready)

  /* ---------- Flush ---------------- */

  when(io.flush) {
    enq_ptr.reset()
    deq_ptr.reset()
    maybe_full := false.B
    for (i <- 0 until StoreQueueSize) {
      sq(i).valid := false.B
    }
    // todo: when flush, how to handle remaining deq request?

    if (DebugMsgStoreQueue) {
      printf("%d: [SQ - F]\n", DebugTimer())
    }
  }

}
