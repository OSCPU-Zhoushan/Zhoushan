package zhoushan

import chisel3._
import chisel3.util._

class Uncache(id: Int) extends Module with ZhoushanConfig {
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
  out.req.bits.size   := 1.U

  val resp_id = HoldUnless(in.req.bits.id, in.req.fire())

  out.resp.ready      := in.resp.ready
  in.resp.valid       := out.resp.valid
  in.resp.bits.id     := resp_id
  in.resp.bits.rdata  := out.resp.bits.rdata
  in.resp.bits.user   := 0.U
}
