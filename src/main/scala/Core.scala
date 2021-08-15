package zhoushan

import chisel3._

class Core extends Module {
  val io = IO(new Bundle {
    val pc = Output(UInt(32.W))
    val inst = Input(UInt(32.W))
    val rf_debug = Output(Vec(32, UInt(64.W)))
  })

  val pc = RegInit(0.U(64.W))
  pc := pc + 4.U
  io.pc := pc

  val decode = Module(new Decode())
  decode.io.pc := pc
  decode.io.inst := io.inst

  val uop = decode.io.uop

  val regFile = Module(new RegFile())
  regFile.io.rs1_addr := uop.rs1_addr
  regFile.io.rs2_addr := uop.rs2_addr
  regFile.io.rd_addr := uop.rd_addr
  regFile.io.rd_en := uop.rd_en
  io.rf_debug := regFile.io.rf_debug
  
  val execution = Module(new Execution())
  execution.io.uop := uop
  execution.io.rs1_data := regFile.io.rs1_data
  execution.io.rs2_data := regFile.io.rs2_data
  regFile.io.rd_data := execution.io.out_data
}
