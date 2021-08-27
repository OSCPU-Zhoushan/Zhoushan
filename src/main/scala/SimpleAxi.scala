package zhoushan

import chisel3._
import chisel3.util._

// Simple Axi is an simplified bus implementation modified from AXI4
// AXI4       - Duplex
// SimpleAxi  - Simplex, enough for IF (read only) and MEM stage

trait SimpleAxiId extends Bundle with AxiParameters {
  val id = Output(UInt(AxiIdWidth.W))
}

class SimpleAxiReq extends Bundle with SimpleAxiId with AxiParameters {
  val addr = Output(UInt(AxiAddrWidth.W))
  val ren = Output(Bool())
  val wdata = Output(UInt(AxiDataWidth.W))
  val wmask = Output(UInt((AxiDataWidth / 8).W))
  val wlast = Output(Bool())
  val wen = Output(Bool())
  val len = Output(UInt(8.W))
}

class SimpleAxiResp extends Bundle with SimpleAxiId with AxiParameters {
  val rdata = Output(UInt(AxiDataWidth.W))
  val wresp = Output(Bool())
  val rlast = Output(Bool())
}

class SimpleAxiIO extends MemIO {
  val req = Decoupled(new SimpleAxiReq)
  val resp = Flipped(Decoupled(new SimpleAxiResp))
}

class SimpleAxi2Axi extends Module with AxiParameters {
  val io = IO(new Bundle {
    val in = Flipped(new SimpleAxiIO)
    val out = new AxiIO
  })

  val in = io.in
  val out = io.out

  /* ----- SimpleAxi -> AXI4 -- Request --------------------------- */

  out.aw.valid      := in.req.valid && in.req.bits.wen
  out.aw.bits.addr  := in.req.bits.addr
  out.aw.bits.prot  := "b001".U         // privileged access
  out.aw.bits.id    := in.req.bits.id
  out.aw.bits.user  := 0.U
  out.aw.bits.len   := in.req.bits.len
  out.aw.bits.size  := "b011".U         // 8 bytes in transfer
  out.aw.bits.burst := "b01".U          // INCR mode, not used so far
  out.aw.bits.lock  := false.B
  out.aw.bits.cache := 0.U
  out.aw.bits.qos   := 0.U

  out.w.valid       := in.req.valid && in.req.bits.wen
  out.w.bits.data   := in.req.bits.wdata
  out.w.bits.strb   := in.req.bits.wmask
  out.w.bits.last   := in.req.bits.wlast

  out.ar.valid      := in.req.valid && in.req.bits.ren
  out.ar.bits.addr  := in.req.bits.addr
  out.ar.bits.prot  := "b001".U         // privileged access
  out.ar.bits.id    := in.req.bits.id
  out.ar.bits.user  := 0.U
  out.ar.bits.len   := in.req.bits.len
  out.ar.bits.size  := "b011".U         // 8 bytes in transfer
  out.ar.bits.burst := "b01".U          // INCR mode, not used so far
  out.ar.bits.lock  := false.B
  out.ar.bits.cache := 0.U
  out.ar.bits.qos   := 0.U

  /* ----- SimpleAxi -> AXI4 -- Response -------------------------- */

  out.b.ready := in.resp.ready
  // in.resp.valid <- out.b.valid

  out.r.ready := in.resp.ready
  // in.resp.valid <- out.r.valid

  /* ----- SimpleAxi Input Ctrl Signal Logic ---------------------- */

  // in.req.ready  <- out.aw.ready/out.ar.ready/out.w.ready
  // in.resp.valid <- out.b.valid/out.r.valid
  // Currently we are using a conservative logic here
  // todo: optimize the logic by implementing a state machine
  in.req.ready       := (out.aw.ready && out.w.ready) || out.ar.ready
  in.resp.valid      := out.b.valid || out.r.valid
  in.resp.bits.id    := Mux(out.b.valid, out.b.bits.id,
                        Mux(out.r.valid, out.r.bits.id, 0.U))
  in.resp.bits.rdata := out.r.bits.data
  in.resp.bits.wresp := out.b.valid
  in.resp.bits.rlast := out.r.valid & out.r.bits.last

}
