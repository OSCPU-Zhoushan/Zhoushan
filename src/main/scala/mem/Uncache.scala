package zhoushan

import chisel3._
import chisel3.util._

class Uncache[BT <: CacheBusIO](bus_type: BT, id: Int) extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(bus_type)
    val out = new CoreBusIO
  })

  val in = io.in
  val out = io.out

  // state machine to handle 64-bit data transfer for MMIO
  val s_idle :: s_req_1 :: s_wait_1 :: s_req_2 :: s_wait_2 :: s_complete :: Nil = Enum(6)
  val state = RegInit(s_idle)

  // registers for req
  val addr  = RegInit(0.U(32.W))
  val wdata = RegInit(0.U(64.W))
  val wmask = RegInit(0.U(8.W))
  val wen   = RegInit(false.B)
  val size  = RegInit(0.U(2.W))
  val in_id = RegInit(0.U(AxiParameters.AxiIdWidth.W))
  val in_user = RegInit(0.U(CacheBusParameters.CacheBusUserWidth.W))

  // registers for resp
  val rdata_1 = RegInit(0.U(32.W))
  val rdata_2 = RegInit(0.U(32.W))

  // if target is SoC, split 64-bit request into 2 32-bit requests
  val req_split = WireInit(false.B)
  req_split := (size === "b11".U) && (addr(2, 0) === 0.U)

  switch (state) {
    is (s_idle) {
      when (in.req.fire()) {
        addr  := in.req.bits.addr
        wdata := in.req.bits.wdata
        wmask := in.req.bits.wmask
        wen   := in.req.bits.wen
        size  := in.req.bits.size
        in_id := in.req.bits.id
        if (bus_type.getClass == classOf[CacheBusWithUserIO]) {
          val in_with_user = in.asInstanceOf[CacheBusWithUserIO]
          in_user := in_with_user.req.bits.user
        }

        rdata_1 := 0.U
        rdata_2 := 0.U
        state := Mux(in.req.bits.addr(2) === 0.U, s_req_1, s_req_2)

        if (DebugUncache) {
          printf("%d: [UN $ ] [REQ ] addr=%x size=%x id=%x\n", DebugTimer(),
                 in.req.bits.addr, in.req.bits.size, in.req.bits.id)
        }
      }
    }
    is (s_req_1) {
      when (out.req.fire()) {
        state := s_wait_1
      }
    }
    is (s_wait_1) {
      when (out.resp.fire()) {
        rdata_1 := out.resp.bits.rdata(31, 0)
        state := Mux(req_split, s_req_2, s_complete)
      }
    }
    is (s_req_2) {
      when (out.req.fire()) {
        state := s_wait_2
      }
    }
    is (s_wait_2) {
      when (out.resp.fire()) {
        if (TargetOscpuSoc) {
          rdata_2 := out.resp.bits.rdata(31, 0)
        } else {
          rdata_2 := out.resp.bits.rdata(63, 32)
        }
        state := s_complete
      }
    }
    is (s_complete) {
      when (in.resp.fire()) {
        state := s_idle
        if (DebugUncache) {
          printf("%d: [UN $ ] [RESP] addr=%x rdata=%x id=%x\n", DebugTimer(),
                 addr, Cat(rdata_2, rdata_1), in_id)
        }
      }
    }
  }

  in.req.ready         := (state === s_idle)
  out.req.valid        := (state === s_req_1 || state === s_req_2)
  out.req.bits.id      := id.U
  out.req.bits.aen     := true.B
  out.req.bits.wlast   := true.B
  out.req.bits.wen     := wen
  out.req.bits.len     := 0.U

  out.resp.ready       := (state === s_wait_1 || state === s_wait_2)
  in.resp.valid        := (state === s_complete)
  in.resp.bits.rdata   := Cat(rdata_2, rdata_1)
  in.resp.bits.id      := in_id
  if (bus_type.getClass == classOf[CacheBusWithUserIO]) {
    val in_with_user = in.asInstanceOf[CacheBusWithUserIO]
    in_with_user.resp.bits.user := in_user
  }

  if (TargetOscpuSoc) {
    out.req.bits.addr  := Mux(req_split && (state === s_req_2), addr + 4.U, addr)
    out.req.bits.wdata := wdata
    out.req.bits.wmask := wmask
    out.req.bits.size  := Mux(req_split, "b10".U, size)
  } else {
    out.req.bits.addr  := Cat(addr(31, 3), Fill(3, 0.U))
    out.req.bits.wdata := wdata
    out.req.bits.wmask := wmask
    out.req.bits.size  := "b11".U
  }

}
