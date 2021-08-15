package zhoushan

import chisel3._
import difftest._

class SimTop extends Module {
  val io = IO(new Bundle {
    val pc = Output(UInt(32.W))
    val inst = Input(UInt(32.W))
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
  })

  val core = Module(new Core())
	core.io.inst := io.inst
  io.pc := core.io.pc

  // val log_begin, log_end, log_level = WireInit(0.U(64.W))
  // log_begin := io.logCtrl.log_begin
  // log_end   := io.logCtrl.log_end
  // log_level := io.logCtrl.log_level

  // val perf_info_clean, perf_info_dump = WireInit(false.B)
  // perf_info_clean := io.perfInfo.clean
  // perf_info_dump  := io.perfInfo.dump

  io.uart.out.valid := false.B
  io.uart.out.ch := 0.U
  io.uart.in.valid := false.B
}
