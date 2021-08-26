package zhoushan

import chisel3._
import chisel3.util._

class Crossbar2to1 extends Module {
  val io = IO(new Bundle {
    // val id = Vec(2, UInt(AxiParameters.AxiIdWidth.W))
    val in = Flipped(Vec(2, new SimpleAxiIO))
    val out = new SimpleAxiIO
  })

  val arbiter = Module(new RRArbiter(new SimpleAxiReq, 2))
  val chosen = RegInit(UInt(1.W), 0.U)
  arbiter.io.in(0) <> io.in(0).req
  arbiter.io.in(1) <> io.in(1).req

  // req logic
  io.in(0).req.ready := (arbiter.io.chosen === 0.U)
  io.in(1).req.ready := (arbiter.io.chosen === 1.U)
  (io.out.req, arbiter.io.out) match { case (l, r) => {
    l.bits := r.bits
    l.valid := r.valid
    r.ready := l.ready
  }}

  // resp logic - send to corresponding master device
  io.in(0).resp.bits := io.out.resp.bits
  io.in(1).resp.bits := io.out.resp.bits
  when (io.out.resp.bits.id === 1.U) {          // to InstFetch
    io.out.resp.ready := io.in(0).resp.ready
    io.in(0).resp.valid := io.out.resp.valid
    io.in(1).resp.valid := false.B
  } .elsewhen (io.out.resp.bits.id === 2.U) {   // to LSU
    io.out.resp.ready := io.in(1).resp.ready
    io.in(0).resp.valid := false.B
    io.in(1).resp.valid := io.out.resp.valid
  } .otherwise {
    io.out.resp.ready := false.B
    io.in(0).resp.valid := false.B
    io.in(1).resp.valid := false.B
  }

}

class Crossbar1to2 extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new SimpleAxiIO)
    val out = Vec(2, new SimpleAxiIO)
  })

  // 0 -> dmem
  // 1 -> CLINT

  val ClintAddrBase = Settings.ClintAddrBase
  val ClintAddrSize = Settings.ClintAddrSize

  val addr = io.in.req.bits.addr
  val to_clint = (addr >= ClintAddrBase && addr < ClintAddrBase + ClintAddrSize)

  // req logic
  io.out(0).req.bits := io.in.req.bits
  io.out(1).req.bits := io.in.req.bits
  io.out(0).req.valid := io.in.req.valid && !to_clint
  io.out(1).req.valid := io.in.req.valid && to_clint
  io.in.req.ready := Mux(to_clint, io.out(1).req.ready, io.out(0).req.ready)

  val arbiter = Module(new RRArbiter(new SimpleAxiResp, 2))
  arbiter.io.in(0) <> io.out(0).resp
  arbiter.io.in(1) <> io.out(1).resp

  // resp logic
  io.out(0).resp.ready := (arbiter.io.chosen === 0.U)
  io.out(1).resp.ready := (arbiter.io.chosen === 1.U)
  (io.in.resp, arbiter.io.out) match { case (l, r) => {
    l.bits := r.bits
    l.valid := r.valid
    r.ready := l.ready
  }}

}
