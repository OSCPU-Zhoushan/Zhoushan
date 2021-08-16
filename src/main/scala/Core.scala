package zhoushan

import chisel3._
import difftest._

class Core extends Module {
  val io = IO(new Bundle {
    val pc = Output(UInt(32.W))
    val inst = Input(UInt(32.W))
  })

  val pc_init = "h80000000".U(32.W)

  val pc = RegInit(pc_init)
  io.pc := pc

  val decode = Module(new Decode)
  decode.io.pc := pc
  decode.io.inst := io.inst

  val uop = decode.io.uop

  val rf = Module(new RegFile)
  rf.io.rs1_addr := uop.rs1_addr
  rf.io.rs2_addr := uop.rs2_addr
  rf.io.rd_addr := uop.rd_addr
  rf.io.rd_en := uop.rd_en
  
  val execution = Module(new Execution)
  execution.io.uop := uop
  execution.io.rs1_data := rf.io.rs1_data
  execution.io.rs2_data := rf.io.rs2_data
  rf.io.rd_data := execution.io.out

  val pc_zero_reset = RegInit(true.B) // todo: fix pc reset
  pc_zero_reset := false.B
  pc := Mux(pc_zero_reset, pc_init, execution.io.next_pc)

  val uop_commit = RegNext(uop)
  val uop_out_valid = RegNext(execution.io.out_valid)
  val dt_ic = Module(new DifftestInstrCommit)
  dt_ic.io.clock    := clock
  dt_ic.io.coreid   := 0.U
  dt_ic.io.index    := 0.U
  dt_ic.io.valid    := uop_commit.valid & uop_out_valid
  dt_ic.io.pc       := uop_commit.pc
  dt_ic.io.instr    := uop_commit.inst
  dt_ic.io.skip     := false.B
  dt_ic.io.isRVC    := false.B
  dt_ic.io.scFailed := false.B
  dt_ic.io.wen      := uop_commit.rd_en
  dt_ic.io.wdata    := RegNext(execution.io.out)
  dt_ic.io.wdest    := uop_commit.rd_addr

  // printf("valid = %x, pc = %x, inst = %x, wen = %x, wdata = %x, wdest = %x\n",
  //        dt_ic.io.valid, dt_ic.io.pc, dt_ic.io.instr, dt_ic.io.wen, dt_ic.io.wdata, dt_ic.io.wdest)

  // printf("valid=%x, pc=%x, inst=%x, fu_code=%x, rs1=%x, rs2=%x, rd=%x, imm=%x\n",
  //        uop_commit.valid, uop_commit.pc, uop_commit.inst, uop_commit.fu_code,
  //        uop_commit.rs1_addr, uop_commit.rs2_addr, uop_commit.rd_addr, uop_commit.imm)

  val dt_cs = Module(new DifftestCSRState)
  dt_cs.io.clock          := clock
  dt_cs.io.coreid         := 0.U
  dt_cs.io.priviledgeMode := 0.U
  dt_cs.io.mstatus        := 0.U
  dt_cs.io.sstatus        := 0.U
  dt_cs.io.mepc           := 0.U
  dt_cs.io.sepc           := 0.U
  dt_cs.io.mtval          := 0.U
  dt_cs.io.stval          := 0.U
  dt_cs.io.mtvec          := 0.U
  dt_cs.io.stvec          := 0.U
  dt_cs.io.mcause         := 0.U
  dt_cs.io.scause         := 0.U
  dt_cs.io.satp           := 0.U
  dt_cs.io.mip            := 0.U
  dt_cs.io.mie            := 0.U
  dt_cs.io.mscratch       := 0.U
  dt_cs.io.sscratch       := 0.U
  dt_cs.io.mideleg        := 0.U
  dt_cs.io.medeleg        := 0.U

  val dt_ae = Module(new DifftestArchEvent)
  dt_ae.io.clock        := clock
  dt_ae.io.coreid       := 0.U
  dt_ae.io.intrNO       := 0.U
  dt_ae.io.cause        := 0.U
  dt_ae.io.exceptionPC  := 0.U

  val dt_te = Module(new DifftestTrapEvent)
  dt_te.io.clock    := clock
  dt_te.io.coreid   := 0.U
  dt_te.io.valid    := false.B
  dt_te.io.code     := 0.U
  dt_te.io.pc       := 0.U
  dt_te.io.cycleCnt := 0.U
  dt_te.io.instrCnt := 0.U

}
