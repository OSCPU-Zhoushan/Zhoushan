package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import difftest._

class Core extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val imem = new CoreBusIO
    val dmem = new CoreBusIO
  })

  val flush = WireInit(false.B)

  /* ----- Stage 1 - Instruction Fetch (IF) ------ */

  val fetch = Module(new InstFetch)

  val icache = Module(new Cache(1))
  icache.io.in <> fetch.io.imem
  icache.io.out <> io.imem

  /* ----- Stage 2 - Instruction Buffer (IB) ----- */

  val ibuf = Module(new InstBuffer)
  ibuf.io.in <> fetch.io.out
  ibuf.io.flush := flush

  /* ----- Stage 3 - Instruction Decode (ID) ----- */

  val decode = Module(new Decode)
  decode.io.in <> ibuf.io.out
  decode.io.flush := flush

  /* ----- Stage 4 - Instruction Issue (IS) ------ */

  val iq = Module(new IssueQueue)
  iq.io.in <> decode.io.out
  iq.io.flush := flush

  /* ----- Stage 5 - Register File (RF) ---------- */

  val rf = Module(new RegFile)
  rf.io.in <> iq.io.out
  rf.io.flush := flush

  /* ----- Stage 6 - Execution (EX) -------------- */

  val execution = Module(new Execution)
  execution.io.in <> rf.io.out

  val ex_rs1_data = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))
  val ex_rs2_data = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))

  execution.io.rs1_data := ex_rs1_data
  execution.io.rs2_data := ex_rs2_data

  val crossbar1to2 = Module(new CacheBusCrossbar1to2)
  crossbar1to2.io.in <> execution.io.dmem

  val dcache = Module(new Cache(2))
  dcache.io.in <> crossbar1to2.io.out(0)
  dcache.io.out <> io.dmem

  val clint = Module(new Clint)
  clint.io.in <> crossbar1to2.io.out(1)

  /* ----- Stage 7 - Commit (CM) ----------------- */

  rf.io.rd_en := execution.io.rd_en
  rf.io.rd_addr := execution.io.rd_addr
  rf.io.rd_data := execution.io.rd_data

  /* ----- Forwarding Unit ----------------------- */

  val intr = execution.io.intr
  val uop_commit = Wire(Vec(IssueWidth, new MicroOp))
  for (i <- 0 until IssueWidth) {
    uop_commit(i) := Mux(!intr, execution.io.out.vec(i), 0.U.asTypeOf(new MicroOp))
  }
  val ex_rs1_from_cm = WireInit(VecInit(Seq.fill(IssueWidth)(VecInit(Seq.fill(IssueWidth)(false.B)))))
  val ex_rs2_from_cm = WireInit(VecInit(Seq.fill(IssueWidth)(VecInit(Seq.fill(IssueWidth)(false.B)))))
  for (i <- 0 until IssueWidth) {
    ex_rs1_data(i) := rf.io.rs1_data(i)
    ex_rs2_data(i) := rf.io.rs2_data(i)
    for (j <- 0 until IssueWidth) {
      ex_rs1_from_cm(i)(j) := uop_commit(j).valid && uop_commit(j).rd_en &&
                              (uop_commit(j).rd_addr =/= 0.U) &&
                              (uop_commit(j).rd_addr === execution.io.in.bits.vec(i).rs1_addr)
      ex_rs2_from_cm(i)(j) := uop_commit(j).valid && uop_commit(j).rd_en &&
                              (uop_commit(j).rd_addr =/= 0.U) &&
                              (uop_commit(j).rd_addr === execution.io.in.bits.vec(i).rs2_addr)
      when (ex_rs1_from_cm(i)(j)) {
        ex_rs1_data(i) := RegNext(execution.io.rd_data(j))
      }
      when (ex_rs2_from_cm(i)(j)) {
        ex_rs2_data(i) := RegNext(execution.io.rd_data(j))
      }
    }
  }

  /* ----- Pipeline Control Signals -------------- */

  fetch.io.jmp_packet <> execution.io.jmp_packet
  flush := execution.io.jmp_packet.mis

  /* ----- Difftest ------------------------------ */

  val lsu_addr = WireInit(UInt(64.W), 0.U)
  BoringUtils.addSink(lsu_addr, "lsu_addr")

  val ClintAddrBase = Settings.ClintAddrBase.U
  val ClintAddrSize = Settings.ClintAddrSize.U

  if (Settings.Difftest) {
    for (i <- 0 until IssueWidth) {
      val skip = (uop_commit(i).inst === Instructions.PUTCH) ||
                 (uop_commit(i).fu_code === Constant.FU_CSR && uop_commit(i).inst(31, 20) === Csrs.mcycle) ||
                 (uop_commit(i).fu_code === Constant.FU_MEM && lsu_addr >= ClintAddrBase && lsu_addr < ClintAddrBase + ClintAddrSize)

      val dt_ic = Module(new DifftestInstrCommit)
      dt_ic.io.clock    := clock
      dt_ic.io.coreid   := 0.U
      dt_ic.io.index    := i.U
      dt_ic.io.valid    := uop_commit(i).valid
      dt_ic.io.pc       := uop_commit(i).pc
      dt_ic.io.instr    := uop_commit(i).inst
      dt_ic.io.skip     := skip
      dt_ic.io.isRVC    := false.B
      dt_ic.io.scFailed := false.B
      dt_ic.io.wen      := uop_commit(i).rd_en
      dt_ic.io.wdata    := RegNext(execution.io.rd_data(i))
      dt_ic.io.wdest    := uop_commit(i).rd_addr
    }
  }

  val cycle_cnt = RegInit(0.U(64.W))
  val instr_cnt = RegInit(0.U(64.W))

  cycle_cnt := cycle_cnt + 1.U
  instr_cnt := instr_cnt + PopCount(uop_commit.map(_.valid))


  val rf_a0 = WireInit(0.U(64.W))
  BoringUtils.addSink(rf_a0, "rf_a0")
  
  if (Settings.Difftest) {
    if (Settings.DebugMsgUopCommit) {
      for (i <- 0 until IssueWidth) {
        when (uop_commit(i).valid) {
          printf("%d: [UOP %d] pc=%x inst=%x\n", DebugTimer(), i.U, uop_commit(i).pc, uop_commit(i).inst)
        }
      }
    }
    when (execution.io.out.vec(0).inst === Instructions.PUTCH) {
      printf("%c", rf_a0(7, 0))
    }
  }

  if (Settings.Difftest) {
    val dt_te = Module(new DifftestTrapEvent)
    dt_te.io.clock    := clock
    dt_te.io.coreid   := 0.U
    dt_te.io.valid    := (uop_commit(0).inst === "h0000006b".U)
    dt_te.io.code     := rf_a0(2, 0)
    dt_te.io.pc       := uop_commit(0).pc
    dt_te.io.cycleCnt := cycle_cnt
    dt_te.io.instrCnt := instr_cnt
  }

  BoringUtils.addSource(cycle_cnt, "csr_mcycle")
  BoringUtils.addSource(instr_cnt, "csr_minstret")
}
