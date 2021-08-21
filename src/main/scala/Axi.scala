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

  val in = io.in
  val out = io.out

  in.aw.ready       := out.aw.ready
  out.aw.valid      := in.aw.valid
  out.aw.bits.addr  := in.aw.bits.addr
  out.aw.bits.prot  := in.aw.bits.prot
  out.aw.bits.id    := 0.U
  out.aw.bits.user  := 0.U
  out.aw.bits.len   := 0.U
  out.aw.bits.size  := 0.U
  out.aw.bits.burst := 0.U
  out.aw.bits.lock  := false.B
  out.aw.bits.cache := 0.U
  out.aw.bits.qos   := 0.U

  in.w.ready        := out.w.ready
  out.w.valid       := in.w.valid
  out.w.bits.data   := in.w.bits.data
  out.w.bits.strb   := in.w.bits.strb
  out.w.bits.last   := true.B

  out.b.ready       := in.b.ready
  in.b.valid        := out.b.valid
  in.b.bits.resp    := out.b.bits.resp

  in.ar.ready       := out.ar.ready
  out.ar.valid      := in.ar.valid
  out.ar.bits.addr  := in.ar.bits.addr
  out.ar.bits.prot  := in.ar.bits.prot
  out.ar.bits.id    := 0.U
  out.ar.bits.user  := 0.U
  out.ar.bits.len   := 0.U
  out.ar.bits.size  := 0.U
  out.ar.bits.burst := 0.U
  out.ar.bits.lock  := false.B
  out.ar.bits.cache := 0.U
  out.ar.bits.qos   := 0.U

  out.r.ready       := out.r.ready
  in.r.valid        := out.r.valid
  in.r.bits.resp    := out.r.bits.resp
  in.r.bits.data    := out.r.bits.data
  in.r.bits.last    := out.r.bits.last

}
