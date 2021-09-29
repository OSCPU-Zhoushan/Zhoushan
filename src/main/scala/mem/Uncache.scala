package zhoushan

import chisel3._
import chisel3.util._

class Uncache(id: Int) extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = new CoreBusIO
  })

  val in = io.in
  val out = io.out

  // state machine to handle 64-bit data transfer for MMIO
  val s_idle :: s_req_1 :: s_wait_1 :: s_req_2 :: s_wait_2 :: s_complete :: Nil = Enum(6)
  val state = RegInit(s_idle)

  // registers for req
  val addr  = RegInit(0.U(32.W))
  val ren   = RegInit(false.B)
  val wdata = RegInit(0.U(64.W))
  val wmask = RegInit(0.U(8.W))
  val wen   = RegInit(false.B)
  val size  = RegInit(0.U(2.W))
  val in_id = RegInit(0.U(AxiParameters.AxiIdWidth.W))

  // registers for resp
  val rdata_1 = RegInit(0.U(32.W))
  val rdata_2 = RegInit(0.U(32.W))

  // if target is SoC, split 64-bit request into 2 32-bit requests
  val req_split = WireInit(false.B)
  req_split := (size === "b11".U)

  switch (state) {
    is (s_idle) {
      when (in.req.fire()) {
        addr  := in.req.bits.addr
        ren   := in.req.bits.ren
        wdata := in.req.bits.wdata
        wmask := in.req.bits.wmask
        wen   := in.req.bits.wen
        size  := in.req.bits.size
        in_id := in.req.bits.id
        state := s_req_1
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
        if (TargetOscpuSoc) {
          rdata_2 := 0.U
          state := Mux(req_split, s_req_2, s_complete)
        } else {
          rdata_2 := out.resp.bits.rdata(63, 32)
          state := s_complete
        }
      }
    }
    is (s_req_2) {
      when (out.req.fire()) {
        state := s_wait_2
      }
    }
    is (s_wait_2) {
      when (out.resp.fire()) {
        rdata_2 := out.resp.bits.rdata(31, 0)
        state := s_complete
      }
    }
    is (s_complete) {
      when (in.resp.fire()) {
        state := s_idle
      }
    }
  }

  in.req.ready         := (state === s_idle)
  out.req.valid        := (state === s_req_1 || state === s_req_2)
  out.req.bits.id      := id.U
  out.req.bits.aen     := true.B
  out.req.bits.ren     := ren
  out.req.bits.wlast   := true.B
  out.req.bits.wen     := wen
  out.req.bits.len     := 0.U

  out.resp.ready       := (state === s_wait_1 || state === s_wait_2)
  in.resp.valid        := (state === s_complete)
  in.resp.bits.id      := in_id
  in.resp.bits.user    := 0.U

  if (TargetOscpuSoc) {
    out.req.bits.addr  := Mux(state === s_req_1, addr, addr + 4.U)
    out.req.bits.wdata := Mux(state === s_req_1, wdata(31, 0), wdata(63, 32))
    out.req.bits.wmask := Mux(state === s_req_1, wmask(3, 0), wmask(7, 4))
    out.req.bits.size    := Mux(req_split, "b10".U, size)

    in.resp.bits.rdata := Cat(rdata_2, rdata_1)
  } else {
    val addr_offset = addr(2, 0)
    val mask = ("b11111111".U << addr_offset)(7, 0)
    out.req.bits.addr  := Cat(addr(31, 3), Fill(3, 0.U))
    out.req.bits.wdata := (wdata << (addr_offset << 3))(63, 0)
    out.req.bits.wmask := mask & ((wmask << addr_offset)(7, 0))
    out.req.bits.size  := "b11".U

    in.resp.bits.rdata := Cat(rdata_2, rdata_1) >> (addr_offset << 3)
  }

}
