package zhoushan

import chisel3._
import chisel3.util._

class CacheBusReq extends Bundle with AxiParameters {
  val addr = Output(UInt(AxiAddrWidth.W))
  val ren = Output(Bool())
  val wdata = Output(UInt(AxiDataWidth.W))
  val wmask = Output(UInt((AxiDataWidth / 8).W))
  val wen = Output(Bool())
}

class CacheBusResp extends Bundle with AxiParameters {
  val rdata = Output(UInt(AxiDataWidth.W))
}

class CacheBusIO extends MemIO {
  val req = Decoupled(new CacheBusReq)
  val resp = Flipped(Decoupled(new CacheBusResp))
}

class CacheBus2SimpelAxi(id: Int) extends Module with AxiParameters {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = new SimpleAxiIO
  })

  val in = io.in
  val out = io.out

  in.req.ready        := out.req.ready
  out.req.valid       := in.req.valid
  out.req.bits.id     := id.U
  out.req.bits.addr   := in.req.bits.addr
  out.req.bits.ren    := in.req.bits.ren
  out.req.bits.wdata  := in.req.bits.wdata
  out.req.bits.wmask  := in.req.bits.wmask
  out.req.bits.wlast  := true.B
  out.req.bits.wen    := in.req.bits.wen
  out.req.bits.len    := 0.U

  out.resp.ready      := in.resp.ready
  in.resp.valid       := out.resp.valid
  in.resp.bits.rdata  := out.resp.bits.rdata
}
