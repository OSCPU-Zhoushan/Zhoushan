package zhoushan

import chisel3._
import chisel3.util._

class RealTop extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master = new AxiIO
    val slave = Flipped(new AxiIO)
  })

  val core = Module(new Core)

  val crossbar2to1 = Module(new CoreBusCrossbar2to1)
  crossbar2to1.io.in(0) <> core.io.imem
  crossbar2to1.io.in(1) <> core.io.dmem

  val core2axi = Module(new CoreBus2Axi)
  core2axi.in <> crossbar2to1.io.out
  core2axi.out <> io.master

  io.slave.aw.ready    := false.B
  io.slave.w.ready     := false.B
  io.slave.b.valid     := false.B
  io.slave.b.bits.resp := 0.U
  io.slave.b.bits.id   := 0.U
  io.slave.b.bits.user := 0.U
  io.slave.ar.ready    := false.B
  io.slave.r.valid     := false.B
  io.slave.r.bits.resp := 0.U
  io.slave.r.bits.data := 0.U
  io.slave.r.bits.last := false.B
  io.slave.r.bits.id   := 0.U
  io.slave.r.bits.user := 0.U

}
