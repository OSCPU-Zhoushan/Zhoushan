package zhoushan

import chisel3._
import difftest._

class Core extends Module {
  val io = IO(new Bundle {
    val pc = Output(UInt(32.W))
    val inst = Input(UInt(32.W))
  })

  val pc = RegInit("h00000000".U(32.W))
  io.pc := pc

  val decode = Module(new Decode)
  decode.io.pc := pc
  decode.io.inst := io.inst

  val uop = decode.io.uop

  val regFile = Module(new RegFile)
  regFile.io.rs1_addr := uop.rs1_addr
  regFile.io.rs2_addr := uop.rs2_addr
  regFile.io.rd_addr := uop.rd_addr
  regFile.io.rd_en := uop.rd_en
  
  val execution = Module(new Execution)
  execution.io.uop := uop
  execution.io.rs1_data := regFile.io.rs1_data
  execution.io.rs2_data := regFile.io.rs2_data
  regFile.io.rd_data := execution.io.out

  val pc_zero_reset = RegInit(true.B) // todo: fix pc reset
  pc_zero_reset := false.B
  pc := Mux(pc_zero_reset, 0.U, execution.io.next_pc)

  val uop_commit = RegNext(uop)
  val difftest = Module(new DifftestInstrCommit)
  difftest.io.valid    := uop_commit.valid
  difftest.io.pc       := uop_commit.pc
  difftest.io.instr    := uop_commit.inst
  difftest.io.skip     := false.B
  difftest.io.isRVC    := false.B
  difftest.io.scFailed := false.B
  difftest.io.wen      := uop_commit.rd_en
  difftest.io.wdata    := RegNext(execution.io.out)
  difftest.io.wdest    := uop_commit.rd_addr
}
