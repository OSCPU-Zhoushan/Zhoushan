/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

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

class CacheBusCrossbarNto1[RT <: CacheBusReq, BT <: CacheBusIO](req_type: RT, bus_type: BT, n: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(n, bus_type))
    val out = Flipped(Flipped(bus_type))
  })

  val arbiter = Module(new RRArbiter(req_type, n))
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

class CacheBusCrossbar1to2[BT <: CacheBusIO](bus_type: BT) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(bus_type)
    val out = Vec(2, bus_type)
    val to_1 = Input(Bool())
  })

  val in_flight_req = RegInit(VecInit(Seq.fill(2)(0.U(8.W))))
  for (i <- 0 until 2) {
    when (io.out(i).req.fire() && !io.out(i).resp.fire()) {
      in_flight_req(i) := in_flight_req(i) + 1.U
      if (ZhoushanConfig.DebugCrossbar1to2) {
        printf("%d: [CB1-2] in_flight_req(%d)=%d -> %d\n", DebugTimer(), i.U, in_flight_req(i), in_flight_req(i) + 1.U)
      }
    } .elsewhen (io.out(i).resp.fire() && !io.out(i).req.fire()) {
      in_flight_req(i) := in_flight_req(i) - 1.U
      if (ZhoushanConfig.DebugCrossbar1to2) {
        printf("%d: [CB1-2] in_flight_req(%d)=%d -> %d\n", DebugTimer(), i.U, in_flight_req(i), in_flight_req(i) - 1.U)
      }
    }
  }

  val req_0_ready = (in_flight_req(1) === 0.U)
  val req_1_ready = (in_flight_req(0) === 0.U)

  val channel = RegInit(0.U(1.W))
  for (i <- 0 until 2) {
    when (io.out(i).req.fire()) {
      channel := i.U
    }
  }

  // req logic
  io.out(0).req.bits  := io.in.req.bits
  io.out(1).req.bits  := io.in.req.bits
  io.out(0).req.valid := io.in.req.valid && !io.to_1 && req_0_ready
  io.out(1).req.valid := io.in.req.valid && io.to_1 && req_1_ready
  io.in.req.ready     := Mux(io.to_1, io.out(1).req.ready && req_1_ready, io.out(0).req.ready && req_0_ready)

  // resp logic
  io.out(0).resp.ready  := io.in.resp.ready && (channel === 0.U)
  io.out(1).resp.ready  := io.in.resp.ready && (channel === 1.U)
  io.in.resp.bits.id    := 0.U
  if (bus_type.getClass == classOf[CacheBusWithUserIO]) {
    val in = io.in.asInstanceOf[CacheBusWithUserIO]
    in.resp.bits.user   := 0.U
  }
  io.in.resp.bits.rdata := 0.U
  io.in.resp.valid      := false.B
  for (i <- 0 until 2) {
    when (channel === i.U) {
      io.in.resp.bits := io.out(i).resp.bits
      io.in.resp.valid := io.out(i).resp.valid
    }
  }

  if (ZhoushanConfig.DebugCrossbar1to2) {
    val in = io.in
    when (in.req.fire()) {
      printf("%d: [CB1-2] [IN ] [REQ ] addr=%x size=%x id=%x wen=%x wdata=%x wmask=%x\n", DebugTimer(),
             in.req.bits.addr, in.req.bits.size, in.req.bits.id, in.req.bits.wen, in.req.bits.wdata, in.req.bits.wmask)
    }
    when (in.resp.fire()) {
      printf("%d: [CB1-2] [IN ] [RESP] rdata=%x id=%x\n", DebugTimer(),
             in.resp.bits.rdata, in.resp.bits.id)
    }
    for (i <- 0 until 2) {
      val out = io.out(i)
      when (out.req.fire()) {
        printf("%d: [CB1-2] [O-%d] [REQ ] addr=%x size=%x id=%x wen=%x wdata=%x wmask=%x\n", DebugTimer(), i.U,
               out.req.bits.addr, out.req.bits.size, out.req.bits.id, out.req.bits.wen, out.req.bits.wdata, out.req.bits.wmask)
      }
      when (out.resp.fire()) {
        printf("%d: [CB1-2] [O-%d] [RESP] rdata=%x id=%x\n", DebugTimer(), i.U,
                  out.resp.bits.rdata, out.resp.bits.id)
      }
    }
  }

}
