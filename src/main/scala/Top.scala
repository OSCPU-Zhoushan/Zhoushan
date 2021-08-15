package zhoushan

import chisel3._

class Top extends Module {
  val io = IO(new Bundle {
    val pc = Output(UInt(32.W))
    val inst = Input(UInt(32.W))
    val rf_debug = Output(Vec(32, UInt(64.W)))
  })
  val core = Module(new Core())
	io <> core.io
}
