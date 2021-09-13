package zhoushan

import chisel3._
import chisel3.util._

trait CacheBusParameters {
  val CacheBusUserWidth = 64 + ZhoushanConfig.FetchWidth * 2
}

class CacheBusReq extends Bundle with AxiParameters with CacheBusParameters {
  val addr = Output(UInt(AxiAddrWidth.W))
  val ren = Output(Bool())
  val wdata = Output(UInt(AxiDataWidth.W))
  val wmask = Output(UInt((AxiDataWidth / 8).W))
  val wen = Output(Bool())
  val user = Output(UInt(CacheBusUserWidth.W))
}

class CacheBusResp extends Bundle with AxiParameters with CacheBusParameters {
  val rdata = Output(UInt(AxiDataWidth.W))
  val user = Output(UInt(CacheBusUserWidth.W))
}

class CacheBusIO extends Bundle {
  val req = Decoupled(new CacheBusReq)
  val resp = Flipped(Decoupled(new CacheBusResp))
}

class CacheBus2CoreBus(id: Int) extends Module with AxiParameters {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = new CoreBusIO
  })

  val in = io.in
  val out = io.out

  in.req.ready        := out.req.ready
  out.req.valid       := in.req.valid
  out.req.bits.id     := id.U
  out.req.bits.addr   := in.req.bits.addr
  out.req.bits.aen    := true.B
  out.req.bits.ren    := in.req.bits.ren
  out.req.bits.wdata  := in.req.bits.wdata
  out.req.bits.wmask  := in.req.bits.wmask
  out.req.bits.wlast  := true.B
  out.req.bits.wen    := in.req.bits.wen
  out.req.bits.len    := 0.U

  out.resp.ready      := in.resp.ready
  in.resp.valid       := out.resp.valid
  in.resp.bits.rdata  := out.resp.bits.rdata
  in.resp.bits.user   := 0.U
}
