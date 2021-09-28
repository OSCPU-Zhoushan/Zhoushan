package zhoushan

import chisel3._
import chisel3.util._

trait CacheBusParameters {
  val CacheBusUserWidth = 64 + ZhoushanConfig.FetchWidth * 2
}

trait CacheBusId extends Bundle with AxiParameters {
  val id = Output(UInt(AxiIdWidth.W))
}

class CacheBusReq extends Bundle with AxiParameters with CacheBusId with CacheBusParameters {
  val addr = Output(UInt(AxiAddrWidth.W))
  val ren = Output(Bool())
  val wdata = Output(UInt(AxiDataWidth.W))
  val wmask = Output(UInt((AxiDataWidth / 8).W))
  val wen = Output(Bool())
  val user = Output(UInt(CacheBusUserWidth.W))
}

class CacheBusResp extends Bundle with AxiParameters with CacheBusId with CacheBusParameters {
  val rdata = Output(UInt(AxiDataWidth.W))
  val user = Output(UInt(CacheBusUserWidth.W))
}

class CacheBusIO extends Bundle {
  val req = Decoupled(new CacheBusReq)
  val resp = Flipped(Decoupled(new CacheBusResp))
}
