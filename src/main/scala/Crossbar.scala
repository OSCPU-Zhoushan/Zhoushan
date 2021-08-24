package zhoushan

import chisel3._
import chisel3.util._

class Crossbar2to1 extends Module {
  val io = IO(new Bundle {
    // val id = Vec(2, UInt(AxiParameters.AxiIdWidth.W))
    val in = Flipped(Vec(2, new SimpleAxiIO))
    val out = new SimpleAxiIO
  })

  val s_idle :: s_wait_r :: s_wait_w :: Nil = Enum(3)
  val state = RegInit(s_idle)

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
  // arbiter.io.out <> io.out.req

  // resp logic - send to corresponding master device
  io.in(0).resp.bits := io.out.resp.bits
  io.in(1).resp.bits := io.out.resp.bits
  when (io.out.resp.bits.id === 1.U) {
    io.out.resp.ready := io.in(0).resp.ready
    io.in(0).resp.valid := io.out.resp.valid
    io.in(1).resp.valid := false.B
  } .elsewhen (io.out.resp.bits.id === 2.U) {
    io.out.resp.ready := io.in(1).resp.ready
    io.in(0).resp.valid := false.B
    io.in(1).resp.valid := io.out.resp.valid
  } .otherwise {
    io.out.resp.ready := false.B
    io.in(0).resp.valid := false.B
    io.in(1).resp.valid := false.B
  }
  // io.out.resp.ready := io.in(chosen).resp.ready
  // io.in(0).resp.valid := false.B
  // io.in(1).resp.valid := false.B
  // io.in(0).resp <> io.out.resp
  // io.in(1).resp <> io.out.resp

}
