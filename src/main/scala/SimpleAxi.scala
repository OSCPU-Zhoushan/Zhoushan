package zhoushan

import chisel3._

// Simple Axi is an simplified bus implementation modified from AXI4
// AXI4       - Duplex
// SimpleAxi  - Simplex, enough for IF (read only) and MEM stage

trait SimpleAxiId extends Bundle with AxiParameters {
  val id = Output(UInt(AxiIdWidth.W))
}

trait SimpleAxiReq extends Bundle with SimpleAxiId with AxiParameters {
  val addr = Output(UInt(AxiAddrWidth.W))
  val ren = Output(Bool())
  val wdata = Output(UInt(AxiDataWidth.W))
  val wmask = Output(UInt((AxiAddrWidth / 8).W))
  val wen = Output(Bool())
}

class SimpleAxiResp extends Bundle with SimpleAxiId with AxiParameters {
  val rdata = Output(UInt(AxiDataWidth.W))
  val wresp = Output(Bool())
  val rlast = Output(Bool())
}

class SimpleAxiIO extends Bundle {
  val req = Decoupled(new SimpleAxiReq)
  val resp = Decoupled(new SimpleAxiResp)
}

class SimpleAxi2Axi extends Module with AxiParameters {
  val io = IO(new Bundle {
    val in = new SimpleAxiIO
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
  out.aw.bits.len   := 1.U              // no burst, 1 transfer
  out.aw.bits.size  := "b011".U         // 8 bytes in transfer
  out.aw.bits.burst := "b01".U          // INCR mode, not used so far
  out.aw.bits.lock  := false.B
  out.aw.bits.cache := 0.U
  out.aw.bits.qos   := 0.U

  out.w.valid       := in.req.valid && in.req.bits.wen
  out.w.bits.data   := in.w.bits.wdata
  out.w.bits.strb   := in.w.bits.wmask
  out.w.bits.last   := true.B           // only 1 transfer, always true

  out.ar.valid      := in.req.valid && in.req.bits.ren
  out.ar.bits.addr  := in.req.bits.addr
  out.ar.bits.prot  := "b001".U         // privileged access
  out.ar.bits.id    := in.req.bits.id
  out.ar.bits.user  := 0.U
  out.ar.bits.len   := 1.U              // no burst, 1 transfer
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
  in.req.ready := out.aw.ready && out.ar.ready && out.w.ready
  in.resp.valid := out.b.valid || out.r.valid
  in.resp.bits.rdata := out.r.bits.data 
  in.resp.bits.wresp := out.b.valid
  in.resp.bits.rlast := out.r.valid & out.r.bits.last

}
