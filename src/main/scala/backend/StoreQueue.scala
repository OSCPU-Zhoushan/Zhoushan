package zhoushan

import chisel3._
import chisel3.util._

class StoreQueueEntry extends Bundle with AxiParameters {
  val addr = UInt(AxiAddrWidth.W)
  val wdata = UInt(AxiDataWidth.W)
  val wmask = UInt((AxiDataWidth / 8).W)
}

class StoreQueue extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in = Flipped(new CacheBusIO)
    val out = new CacheBusIO
    val deq_req = Input(Bool()) // dequeue request from rob
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

  val deq_fire = WireInit(false.B)
  val deq_req = BoolStopWatch(io.deq_req, deq_fire)
  val deq_valid = !empty && deq_req
  val deq_ready = io.out.req.ready && (deq_state === deq_idle)
  deq_fire := deq_valid && deq_ready

  when (deq_fire) {
    sq(deq_ptr.value) := 0.U.asTypeOf(new StoreQueueEntry)
    deq_ptr.inc()
  }

  io.out.req.valid := deq_valid
  io.out.req.bits.addr := sq(deq_ptr.value).addr
  io.out.req.bits.wdata := sq(deq_ptr.value).wdata
  io.out.req.bits.wmask := sq(deq_ptr.value).wmask
  io.out.req.bits.ren := false.B
  io.out.req.bits.wen := true.B
  io.out.req.bits.user := 0.U

  io.out.resp.ready := (deq_state === deq_wait)

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
  io.in.req.ready := enq_ready

  when (enq_fire) {
    val enq = new StoreQueueEntry
    enq.addr := io.in.req.bits.addr
    enq.wdata := io.in.req.bits.wdata
    enq.wmask := io.in.req.bits.wmask
    sq(enq_ptr.value) := enq
    enq_ptr.inc()
  }

  io.in.resp.valid := (enq_state === enq_wait)
  io.in.resp.bits.rdata := 0.U
  io.in.resp.bits.user := 0.U

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

  val sq_hit = WireInit(false.B)


  /* ---------- Flush ---------------- */

  when(io.flush) {
    enq_ptr.reset()
    deq_ptr.reset()
    maybe_full := false.B
  }

}
