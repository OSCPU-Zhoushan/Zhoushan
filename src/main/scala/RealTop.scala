package zhoushan

import chisel3._
import chisel3.util._

class RealTop extends Module {
  val io = IO(new Bundle {
    val interrupt = Input(Bool())
    val master = if (ZhoushanConfig.EnableOscpuSocAxi) new OscpuSocAxiIO else new AxiIO
    val slave = Flipped(if (ZhoushanConfig.EnableOscpuSocAxi) new OscpuSocAxiIO else new AxiIO)
  })

  val core = Module(new Core)

  val crossbar2to1 = Module(new CoreBusCrossbarNto1(2))
  crossbar2to1.io.in(0) <> core.io.imem
  crossbar2to1.io.in(1) <> core.io.dmem

  val core2axi = Module(new CoreBus2Axi)
  core2axi.in <> crossbar2to1.io.out
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
  if (!ZhoushanConfig.EnableOscpuSocAxi) {
    val slave = io.slave.asInstanceOf[AxiIO]
    slave.b.bits.user := 0.U
    slave.r.bits.user := 0.U
  }

}
