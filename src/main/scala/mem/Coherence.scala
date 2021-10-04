package zhoushan

import chisel3._
import chisel3.util._

class Coherence extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new CoreBusIO)
    val mem = new CoreBusIO
    val probe = new CacheBusIO
  })

  val s_idle :: s_wait :: s_hit :: s_miss_req :: s_miss_wait :: Nil = Enum(5)
  val state = RegInit(s_idle)

}
