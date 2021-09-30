package zhoushan

import chisel3._
import chisel3.util._

trait CacheBusParameters {
  val CacheBusUserWidth = 64 + ZhoushanConfig.FetchWidth * 2
}

object CacheBusParameters extends CacheBusParameters { }

trait CacheBusId extends Bundle with AxiParameters {
  val id = Output(UInt(AxiIdWidth.W))
}

trait CacheBusUser extends Bundle with CacheBusParameters {
  val user = Output(UInt(CacheBusUserWidth.W))
}

class CacheBusReq extends Bundle with CacheBusId with CacheBusUser {
  val addr = Output(UInt(AxiAddrWidth.W))
  val ren = Output(Bool())
  val wdata = Output(UInt(AxiDataWidth.W))
  val wmask = Output(UInt((AxiDataWidth / 8).W))
  val wen = Output(Bool())
  val size = Output(UInt(2.W))
}

class CacheBusResp extends Bundle with CacheBusId with CacheBusUser {
  val rdata = Output(UInt(AxiDataWidth.W))
}

class CacheBusIO extends Bundle {
  val req = Decoupled(new CacheBusReq)
  val resp = Flipped(Decoupled(new CacheBusResp))
}
