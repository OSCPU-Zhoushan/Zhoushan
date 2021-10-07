package zhoushan

import chisel3._
import chisel3.util._

class CacheController[BT <: CacheBusIO](bus_type: BT, id_cache: Int, id_uncache: Int) extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(bus_type)
    val out_cache = new CoreBusIO
    val out_uncache = new CoreBusIO
  })

  val to_uncache = (io.in.req.bits.addr(31) === 0.U)
  val cache = Module(new Cache(bus_type, id_cache))
  val uncache = Module(new Uncache(bus_type, id_uncache))

  val crossbar1to2 = Module(new CacheBusCrossbar1to2(bus_type))
  crossbar1to2.io.to_1 := to_uncache
  crossbar1to2.io.in <> io.in
  crossbar1to2.io.out(0) <> cache.io.in
  crossbar1to2.io.out(1) <> uncache.io.in

  io.out_cache <> cache.io.out
  io.out_uncache <> uncache.io.out

}
