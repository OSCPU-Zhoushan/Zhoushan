package zhoushan

import chisel3._
import chisel3.util._
import difftest._

class SimTop extends Module {
  val io = IO(new Bundle {
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
    val memAXI_0 = new AxiIO
  })

  val core = Module(new Core)

  // val crossbar1to2 = Module(new Crossbar1to2)
  // crossbar1to2.io.in <> core.io.dmem

  // val clint = Module(new Clint)
  // clint.io.in <> crossbar1to2.io.out(1)

  // val crossbar2to1 = Module(new Crossbar2to1)
  // crossbar2to1.io.in(0) <> core.io.imem
  // crossbar2to1.io.in(1) <> crossbar1to2.io.out(0)

  val dmem = Module(new Ram1r1w)
  core.io.dmem <> dmem.io.dmem

  // val simple2axi = Module(new SimpleAxi2Axi)
  // simple2axi.in <> crossbar2to1.io.out
  // simple2axi.out <> io.memAXI_0

  val simple2axi = Module(new SimpleAxi2Axi)
  simple2axi.in <> core.io.imem
  simple2axi.out <> io.memAXI_0

  io.uart.out.valid := false.B
  io.uart.out.ch := 0.U
  io.uart.in.valid := false.B

}
