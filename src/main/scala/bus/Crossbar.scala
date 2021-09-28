package zhoushan

import chisel3._
import chisel3.util._

class CoreBusCrossbarNto1(n: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(n, new CoreBusIO))
    val out = new CoreBusIO
  })

  val arbiter = Module(new RRArbiter(new CoreBusReq, n))
  val chosen = RegInit(UInt(log2Up(n).W), 0.U)
  for (i <- 0 until n) {
    arbiter.io.in(i) <> io.in(i).req
  }

  // req logic
  for (i <- 0 until n) {
    io.in(i).req.ready := (arbiter.io.chosen === i.U) && io.out.req.ready
  }
  (io.out.req, arbiter.io.out) match { case (l, r) => {
    l.bits := r.bits
    l.valid := r.valid
    r.ready := l.ready
  }}

  // resp logic - send to corresponding master device
  for (i <- 0 until n) {
    io.in(i).resp.bits := io.out.resp.bits
    io.in(i).resp.valid := false.B
    io.out.resp.ready := false.B
  }
  for (i <- 0 until n) {
    when (io.out.resp.bits.id === (i + 1).U) {
      io.out.resp.ready := io.in(i).resp.ready
      io.in(i).resp.valid := io.out.resp.valid
    }
  }

}

class CacheBusCrossbarNto1(n: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(n, new CacheBusIO))
    val out = new CacheBusIO
  })

  val arbiter = Module(new RRArbiter(new CacheBusReq, n))
  val chosen = RegInit(UInt(log2Up(n).W), 0.U)
  for (i <- 0 until n) {
    arbiter.io.in(i) <> io.in(i).req
  }

  // req logic
  for (i <- 0 until n) {
    io.in(i).req.ready := (arbiter.io.chosen === i.U) && io.out.req.ready
  }
  (io.out.req, arbiter.io.out) match { case (l, r) => {
    l.bits := r.bits
    l.valid := r.valid
    r.ready := l.ready
  }}

  // resp logic - send to corresponding master device
  for (i <- 0 until n) {
    io.in(i).resp.bits := io.out.resp.bits
    io.in(i).resp.valid := false.B
    io.out.resp.ready := false.B
  }
  for (i <- 0 until n) {
    when (io.out.resp.bits.id === (i + 1).U) {
      io.out.resp.ready := io.in(i).resp.ready
      io.in(i).resp.valid := io.out.resp.valid
    }
  }

}

class CacheBusCrossbar1to2 extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = Vec(2, new CacheBusIO)
    val to_1 = Input(Bool())
  })

  val in_flight_req = RegInit(VecInit(Seq.fill(2)(0.U(8.W))))
  for (i <- 0 until 2) {
    when (io.out(i).req.fire() && !io.out(i).resp.fire()) {
      in_flight_req(i) := in_flight_req(i) + 1.U
    } .elsewhen (io.out(i).resp.fire() && !io.out(i).req.fire()) {
      in_flight_req(i) := in_flight_req(i) - 1.U
    }
  }

  val req_0_ready = (in_flight_req(1) === 0.U)
  val req_1_ready = (in_flight_req(0) === 0.U)

  // req logic
  io.out(0).req.bits := io.in.req.bits
  io.out(1).req.bits := io.in.req.bits
  io.out(0).req.valid := io.in.req.valid && !io.to_1 && req_0_ready
  io.out(1).req.valid := io.in.req.valid && io.to_1 && req_1_ready
  io.in.req.ready := Mux(io.to_1, io.out(1).req.ready && req_1_ready, io.out(0).req.ready && req_0_ready)

  val arbiter = Module(new RRArbiter(new CacheBusResp, 2))
  arbiter.io.in(0) <> io.out(0).resp
  arbiter.io.in(1) <> io.out(1).resp

  // resp logic
  io.out(0).resp.ready := (arbiter.io.chosen === 0.U) && io.in.resp.ready
  io.out(1).resp.ready := (arbiter.io.chosen === 1.U) && io.in.resp.ready
  (io.in.resp, arbiter.io.out) match { case (l, r) => {
    l.bits := r.bits
    l.valid := r.valid
    r.ready := l.ready
  }}

}
