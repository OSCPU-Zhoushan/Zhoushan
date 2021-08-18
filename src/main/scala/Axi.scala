package zhoushan

import chisel3._
import chisel3.util._

trait AxiParameters {
  val AxiAddrWidth = 64
  val AxiDataWidth = 64
  val AxiIdWidth = 4
  val AxiUserWidth = 1
}

trait AxiIdUser extends Bundle with AxiParameters {
  val id = Output(UInt(AxiIdWidth.W))
  val user = Output(UInt(AxiUserWidth.W))
}

class AxiLiteA extends Bundle with AxiParameters {
  val addr = Output(UInt(AxiAddrWidth.W))
  val prot = Output(UInt(3.W))
}

class AxiA extends AxiLiteA with AxiIdUser {
  val len = Output(UInt(8.W))
  val size = Output(UInt(3.W))
  val burst = Output(UInt(2.W))
  val lock = Output(Bool())
  val cache = Output(UInt(4.W))
  val qos = Output(UInt(4.W))
}

class AxiLiteW extends Bundle with AxiParameters {
  val data = Output(UInt(AxiAddrWidth.W))
  val strb = Output(UInt((AxiAddrWidth / 8).W))
}

class AxiW extends AxiLiteW {
  val last = Output(Bool())
}

class AxiLiteB extends Bundle {
  val resp = Output(UInt(2.W))
}

class AxiB extends AxiLiteB with AxiIdUser with AxiParameters {

}

class AxiLiteR extends Bundle with AxiParameters {
  val resp = Output(UInt(2.W))
  val data = Output(UInt(AxiAddrWidth.W))
}

class AxiR extends AxiLiteR with AxiIdUser {
  val last = Output(Bool())
}

class AxiLiteIO extends Bundle {
  val aw = Decoupled(new AxiLiteA)
  val w = Decoupled(new AxiLiteW)
  val b = Flipped(Decoupled(new AxiLiteB))
  val ar = Decoupled(new AxiLiteA)
  val r = Flipped(Decoupled(new AxiLiteR))
}

class AxiIO extends Bundle {
  val aw = Decoupled(new AxiA)
  val w = Decoupled(new AxiW)
  val b = Flipped(Decoupled(new AxiB))
  val ar = Decoupled(new AxiA)
  val r = Flipped(Decoupled(new AxiR))
}

class AxiLite2Axi extends Module {
  val io = IO(new Bundle {
    val in = new AxiLiteIO
    val out = new AxiIO
  })

  io.in.aw.ready       := io.out.aw.ready
  io.out.aw.valid      := io.in.aw.valid
  io.out.aw.bits.addr  := io.in.aw.bits.addr
  io.out.aw.bits.prot  := io.in.aw.bits.prot
  io.out.aw.bits.id    := 0.U
  io.out.aw.bits.user  := 0.U
  io.out.aw.bits.len   := 0.U
  io.out.aw.bits.size  := 0.U
  io.out.aw.bits.burst := 0.U
  io.out.aw.bits.lock  := false.B
  io.out.aw.bits.cache := 0.U
  io.out.aw.bits.qos   := 0.U

  io.in.w.ready        := io.out.w.ready
  io.out.w.valid       := io.in.w.valid
  io.out.w.bits.data   := io.in.w.bits.data
  io.out.w.bits.strb   := io.in.w.bits.data
  io.out.w.bits.last   := true.B

  io.out.b.ready       := io.in.b.ready
  io.in.b.valid        := io.out.b.valid
  io.in.b.bits.resp    := io.out.b.bits.resp

  io.in.ar.ready       := io.out.ar.ready
  io.out.ar.valid      := io.in.ar.valid
  io.out.ar.bits.addr  := io.in.ar.bits.addr
  io.out.ar.bits.prot  := io.in.ar.bits.prot
  io.out.ar.bits.id    := 0.U
  io.out.ar.bits.user  := 0.U
  io.out.ar.bits.len   := 0.U
  io.out.ar.bits.size  := 0.U
  io.out.ar.bits.burst := 0.U
  io.out.ar.bits.lock  := false.B
  io.out.ar.bits.cache := 0.U
  io.out.ar.bits.qos   := 0.U

  io.out.r.ready       := io.out.r.ready
  io.in.r.valid        := io.out.r.valid
  io.in.r.bits.resp    := io.out.r.bits.resp
  io.in.r.bits.data    := io.out.r.bits.data

}
