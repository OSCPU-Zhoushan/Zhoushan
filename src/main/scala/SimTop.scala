package zhoushan

import chisel3._
import difftest._

class SimTop extends Module {
  val io = IO(new Bundle {
    val pc = Output(UInt(32.W))
    val inst = Input(UInt(32.W))
    val rf_debug = Output(Vec(32, UInt(64.W)))
    // val logCtrl = new LogCtrlIO
    // val perfInfo = new PerfInfoIO
    // val uart = new UARTIO
  })
  val core = Module(new Core())
	io <> core.io
}
