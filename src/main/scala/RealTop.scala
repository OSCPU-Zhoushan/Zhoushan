package zhoushan

import chisel3._
import chisel3.util._

class RealTop extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master = new OscpuSocAxiIO
    val slave = Flipped(new OscpuSocAxiIO)
  })

  val core = Module(new Core)

  val crossbar = Module(new CoreBusCrossbarNto1(4))
  crossbar.io.in <> core.io.core_bus

  val core2axi = Module(new CoreBus2Axi(new OscpuSocAxiIO))
  core2axi.in <> crossbar.io.out
  core2axi.out <> io.master

  val slave = io.slave
  slave.aw.ready    := false.B
  slave.w.ready     := false.B
  slave.b.valid     := false.B
  slave.b.bits.resp := 0.U
  slave.b.bits.id   := 0.U
  slave.ar.ready    := false.B
  slave.r.valid     := false.B
  slave.r.bits.resp := 0.U
  slave.r.bits.data := 0.U
  slave.r.bits.last := false.B
  slave.r.bits.id   := 0.U

}
