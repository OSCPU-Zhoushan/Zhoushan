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
  val dmem = Module(new Ram1r1w)
  core.io.dmem <> dmem.io.dmem
  core.io.imem <> io.memAXI_0

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
