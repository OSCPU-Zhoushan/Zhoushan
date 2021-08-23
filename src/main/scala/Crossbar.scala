package zhoushan

import chisel3._
import chisel3.util._

class Crossbar2to1 extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(2, new SimpleAxiIO))
    val out = new SimpleAxiIO
  })

  val s_idle :: s_wait_r :: s_wait_w :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val arbiter = Module(new RRArbiter(new SimpleAxiReq, 2))
  val chosen = RegInit(UInt(1.W), 0.U)
  arbiter.io.in(0) <> io.in(0).req
  arbiter.io.in(1) <> io.in(1).req

  switch (state) {
    is (s_idle) {
      when (io.out.req.fire()) {
        chosen := arbiter.io.chosen
        when (io.out.req.bits.ren) {
          state := s_wait_r
        } .elsewhen (io.out.req.bits.wen) {
          state := s_wait_w
        }
      }
    }
    is (s_wait_r) {
      when (io.out.resp.fire() && io.out.resp.bits.rlast) {
        state := s_idle
      }
    }
    is (s_wait_w) {
      when (io.out.resp.fire() && io.out.resp.bits.wresp) {
        state := s_idle
      }
    }
  }

  // req logic
  io.in(0).req.ready := (arbiter.io.chosen === 0.U)
  io.in(1).req.ready := (arbiter.io.chosen === 1.U)
  (io.out.req, arbiter.io.out) match { case (l, r) => {
    l.bits := r.bits
    l.valid := (state === s_idle) && r.valid
    r.ready := (state === s_idle) && l.ready
  }}

  // resp logic
  io.in(0).resp.bits := io.out.resp.bits
  io.in(1).resp.bits := io.out.resp.bits
  io.out.resp.ready := io.in(chosen).resp.ready
  io.in(0).resp.valid := false.B
  io.in(1).resp.valid := false.B
  io.in(chosen).resp.valid := io.out.resp.valid

}
