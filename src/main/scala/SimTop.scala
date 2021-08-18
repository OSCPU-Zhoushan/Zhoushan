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
  val mem = Module(new Ram2r1w)
  mem.io <> core.io

  val axi = io.memAXI_0

  axi.aw.valid := false.B
  axi.aw.bits.addr := 0.U
  axi.aw.bits.prot := 0.U
  axi.aw.bits.id := 0.U
  axi.aw.bits.user := 0.U
  axi.aw.bits.len := 0.U
  axi.aw.bits.size := 0.U
  axi.aw.bits.burst := 0.U
  axi.aw.bits.lock := false.B
  axi.aw.bits.cache := 0.U
  axi.aw.bits.qos := 0.U

  axi.w.valid := false.B
  axi.w.bits.data := 0.U
  axi.w.bits.strb := 0.U
  axi.w.bits.last := false.B

  axi.b.ready := false.B

  axi.ar.valid := false.B
  axi.ar.bits.addr := 0.U
  axi.ar.bits.prot := 0.U
  axi.ar.bits.id := 0.U
  axi.ar.bits.user := 0.U
  axi.ar.bits.len := 0.U
  axi.ar.bits.size := 0.U
  axi.ar.bits.burst := 0.U
  axi.ar.bits.lock := false.B
  axi.ar.bits.cache := 0.U
  axi.ar.bits.qos := 0.U

  axi.r.ready := false.B

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
