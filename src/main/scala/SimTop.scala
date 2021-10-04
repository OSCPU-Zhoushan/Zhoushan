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

  val crossbar = Module(new CoreBusCrossbarNto1(4))
  crossbar.io.in <> core.io.core_bus

  val core2axi = Module(new CoreBus2Axi(new AxiIO))
  core2axi.in <> crossbar.io.out
  core2axi.out <> io.memAXI_0

  io.uart.out.valid := false.B
  io.uart.out.ch := 0.U
  io.uart.in.valid := false.B

}
