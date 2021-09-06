package zhoushan

import chisel3._
import chisel3.util.experimental._
import difftest._

class Core extends Module {
  val io = IO(new Bundle {
    val imem = new CoreBusIO
    val dmem = new CoreBusIO
  })

  val stall = WireInit(false.B)
  val flush = WireInit(false.B)

  /* ----- Stage 1 - Instruction Fetch (IF) ------ */

  val fetch = Module(new InstFetch)

  val icache = Module(new Cache(1))
  icache.io.in <> fetch.io.imem
  icache.io.out <> io.imem

  /* ----- Stage 2 - Instruction Buffer (IB) ----- */

  val ibuf = Module(new InstBuffer(8, 1, 1))
  ibuf.io.in <> fetch.io.out
  ibuf.io.flush := flush

  /* ----- Stage 3 - Instruction Decode (ID) ----- */

  val decode = Module(new Decode(1))
  decode.io.in <> ibuf.io.out
  decode.io.backend_ready := !stall

  val rf = Module(new RegFile)
  rf.io.rs1_addr := decode.io.uop.rs1_addr
  rf.io.rs2_addr := decode.io.uop.rs2_addr

  val id_ex_reg = Module(new PipelineReg(new ExPacket))
  id_ex_reg.io.in.uop := decode.io.uop
  id_ex_reg.io.in.rs1_data := rf.io.rs1_data
  id_ex_reg.io.in.rs2_data := rf.io.rs2_data
  id_ex_reg.io.stall := stall
  id_ex_reg.io.flush := flush

  /* ----- Stage 4 - Execution (EX) -------------- */

  val execution = Module(new Execution)
  execution.io.uop := id_ex_reg.io.out.uop

  val ex_rs1_data = WireInit(0.U(64.W))
  val ex_rs2_data = WireInit(0.U(64.W))

  execution.io.rs1_data := ex_rs1_data
  execution.io.rs2_data := ex_rs2_data

  val crossbar1to2 = Module(new CacheBusCrossbar1to2)
  crossbar1to2.io.in <> execution.io.dmem

  val dcache = Module(new Cache(2))
  dcache.io.in <> crossbar1to2.io.out(0)
  dcache.io.out <> io.dmem

  val clint = Module(new Clint)
  clint.io.in <> crossbar1to2.io.out(1)

  /* ----- Stage 5 - Commit (CM) ----------------- */

  rf.io.rd_addr := execution.io.uop.rd_addr
  rf.io.rd_data := execution.io.result
  rf.io.rd_en := execution.io.uop.valid && execution.io.uop.rd_en

  val ex_cm_reg = Module(new PipelineReg(new CommitPacket))

  ex_cm_reg.io.in.uop := execution.io.uop
  ex_cm_reg.io.in.rd_data := execution.io.result
  ex_cm_reg.io.stall := false.B
  ex_cm_reg.io.flush := execution.io.busy

  /* ----- Forwarding Unit ----------------------- */

  val intr = execution.io.intr
  val uop_commit = Mux(!intr, ex_cm_reg.io.out.uop, 0.U.asTypeOf(new MicroOp))
  val ex_rs1_from_cm = uop_commit.valid && uop_commit.rd_en &&
                      (uop_commit.rd_addr =/= 0.U) &&
                      (uop_commit.rd_addr === execution.io.uop.rs1_addr)
  ex_rs1_data := Mux(ex_rs1_from_cm, ex_cm_reg.io.out.rd_data, id_ex_reg.io.out.rs1_data)
  val ex_rs2_from_cm = uop_commit.valid && uop_commit.rd_en &&
                      (uop_commit.rd_addr =/= 0.U) &&
                      (uop_commit.rd_addr === execution.io.uop.rs2_addr)
  ex_rs2_data := Mux(ex_rs2_from_cm, ex_cm_reg.io.out.rd_data, id_ex_reg.io.out.rs2_data)

  /* ----- Pipeline Control Signals -------------- */

  fetch.io.jmp_packet <> execution.io.jmp_packet
  flush := execution.io.jmp_packet.mis
  stall := execution.io.busy

  /* ----- Difftest ------------------------------ */

  val lsu_addr = WireInit(UInt(64.W), 0.U)
  BoringUtils.addSink(lsu_addr, "lsu_addr")

  val ClintAddrBase = Settings.ClintAddrBase.U
  val ClintAddrSize = Settings.ClintAddrSize.U

  val skip = (uop_commit.inst === Instructions.PUTCH) ||
             (uop_commit.fu_code === Constant.FU_CSR && uop_commit.inst(31, 20) === Csrs.mcycle) ||
             (uop_commit.fu_code === Constant.FU_MEM && lsu_addr >= ClintAddrBase && lsu_addr < ClintAddrBase + ClintAddrSize)

  if (Settings.Difftest) {
    val dt_ic = Module(new DifftestInstrCommit)
    dt_ic.io.clock    := clock
    dt_ic.io.coreid   := 0.U
    dt_ic.io.index    := 0.U
    dt_ic.io.valid    := uop_commit.valid
    dt_ic.io.pc       := uop_commit.pc
    dt_ic.io.instr    := uop_commit.inst
    dt_ic.io.skip     := skip
    dt_ic.io.isRVC    := false.B
    dt_ic.io.scFailed := false.B
    dt_ic.io.wen      := uop_commit.rd_en
    dt_ic.io.wdata    := ex_cm_reg.io.out.rd_data
    dt_ic.io.wdest    := uop_commit.rd_addr
  }

  val cycle_cnt = RegInit(0.U(64.W))
  val instr_cnt = RegInit(0.U(64.W))

  cycle_cnt := cycle_cnt + 1.U
  instr_cnt := instr_cnt + Mux(uop_commit.valid, 1.U, 0.U)

  val rf_a0 = WireInit(0.U(64.W))
  BoringUtils.addSink(rf_a0, "rf_a0")
  
  if (Settings.Difftest) {
    if (Settings.DebugMsgUopCommit) {
      when (uop_commit.valid) {
        printf("%d: [UOP] pc=%x inst=%x\n", DebugTimer(), uop_commit.pc, uop_commit.inst)
      }
    }
    when (execution.io.uop.inst === Instructions.PUTCH) {
      printf("%c", rf_a0(7, 0))
    }
  }

  if (Settings.Difftest) {
    val dt_te = Module(new DifftestTrapEvent)
    dt_te.io.clock    := clock
    dt_te.io.coreid   := 0.U
    dt_te.io.valid    := (uop_commit.inst === "h0000006b".U)
    dt_te.io.code     := rf_a0(2, 0)
    dt_te.io.pc       := uop_commit.pc
    dt_te.io.cycleCnt := cycle_cnt
    dt_te.io.instrCnt := instr_cnt
  }

  BoringUtils.addSource(cycle_cnt, "csr_mcycle")
  BoringUtils.addSource(instr_cnt, "csr_minstret")
}
