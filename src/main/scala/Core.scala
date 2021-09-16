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

  /* ----- Stage 4 - Register Renaming (RR) ------ */

  val rename = Module(new Rename)
  rename.io.in <> decode.io.out
  rename.io.flush := flush

  /* ----- Stage 5 - Dispatch (DP) --------------- */

  val rob = Module(new Rob)
  rob.io.in <> rename.io.out
  rob.io.flush := flush

  rename.io.cm_recover := rob.io.jmp_packet.mis
  rename.io.cm := rob.io.cm

  fetch.io.jmp_packet := rob.io.jmp_packet
  flush := rob.io.jmp_packet.mis

  /* ----- Stage 6 - Issue (IS) ------------------ */

  val iq = Module(new IssueQueue)
  iq.io.in <> rob.io.out
  iq.io.flush := flush
  iq.io.avail_list := rename.io.avail_list

  /* ----- Stage 7 - Register File (RF) ---------- */

  val prf = Module(new Prf)
  prf.io.in := iq.io.out
  prf.io.flush := flush

  /* ----- Stage 8 - Execution (EX) -------------- */

  val execution = Module(new Execution)
  execution.io.in <> prf.io.out
  execution.io.flush := flush
  execution.io.rs1_data := prf.io.rs1_data
  execution.io.rs2_data := prf.io.rs2_data

  rename.io.exe := execution.io.out

  rob.io.exe := execution.io.out
  rob.io.exe_ecp := execution.io.out_ecp

  iq.io.lsu_ready := execution.io.lsu_ready

  val crossbar1to2 = Module(new CacheBusCrossbar1to2)
  crossbar1to2.io.in <> execution.io.dmem

  val dcache = Module(new Cache(2))
  dcache.io.in <> crossbar1to2.io.out(0)
  dcache.io.out <> io.dmem

  val clint = Module(new Clint)
  clint.io.in <> crossbar1to2.io.out(1)

  /* ----- Stage 9 - Commit (CM) ----------------- */

  prf.io.rd_en := execution.io.rd_en
  prf.io.rd_paddr := execution.io.rd_paddr
  prf.io.rd_data := execution.io.rd_data

  val cm = rob.io.cm
  val cm_rd_data = rob.io.cm_rd_data

  /* ----- Difftest ------------------------------ */

  val ClintAddrBase = Settings.ClintAddrBase.U
  val ClintAddrSize = Settings.ClintAddrSize.U

  val rf_a0 = WireInit(0.U(64.W))
  BoringUtils.addSink(rf_a0, "rf_a0")

  if (Settings.Difftest) {
    for (i <- 0 until CommitWidth) {
      val skip = (cm(i).inst === Instructions.PUTCH) ||
                 (cm(i).fu_code === Constant.FU_CSR && cm(i).inst(31, 20) === Csrs.mcycle)

      val dt_ic = Module(new DifftestInstrCommit)
      dt_ic.io.clock    := clock
      dt_ic.io.coreid   := 0.U
      dt_ic.io.index    := i.U
      dt_ic.io.valid    := cm(i).valid
      dt_ic.io.pc       := cm(i).pc
      dt_ic.io.instr    := cm(i).inst
      dt_ic.io.skip     := skip
      dt_ic.io.isRVC    := false.B
      dt_ic.io.scFailed := false.B
      dt_ic.io.wen      := cm(i).rd_en
      dt_ic.io.wdata    := cm_rd_data(i)
      dt_ic.io.wdest    := cm(i).rd_addr

      when (cm(i).inst === Instructions.PUTCH) {
        printf("%c", rf_a0(7, 0))
      }

      if (Settings.DebugMsgUopCommit) {
        val u = cm(i)
        when (u.valid) {
          printf("%d: [UOP %d] pc=%x inst=%x rs1=%d->%d rs2=%d->%d rd(en=%x)=%d->%d\n", DebugTimer(), i.U, 
                 u.pc, u.inst, u.rs1_addr, u.rs1_paddr, u.rs2_addr, u.rs2_paddr, u.rd_en, u.rd_addr, u.rd_paddr)
        }
      }
    }
  }

  val cycle_cnt = RegInit(0.U(64.W))
  val instr_cnt = RegInit(0.U(64.W))

  cycle_cnt := cycle_cnt + 1.U
  instr_cnt := instr_cnt + PopCount(cm.map(_.valid))

  if (Settings.Difftest) {
    val trap = Cat(cm.map(_.inst === "h0000006b".U).reverse)
    val trap_idx = OHToUInt(trap)

    val dt_te = Module(new DifftestTrapEvent)
    dt_te.io.clock    := clock
    dt_te.io.coreid   := 0.U
    dt_te.io.valid    := trap.orR
    dt_te.io.code     := rf_a0(2, 0)
    dt_te.io.pc       := 0.U
    for (i <- 0 until CommitWidth) {
      when (trap_idx === i.U) {
        dt_te.io.pc   := cm(i).pc
      }
    }
    dt_te.io.cycleCnt := cycle_cnt
    dt_te.io.instrCnt := instr_cnt
  }

  // BoringUtils.addSource(cycle_cnt, "csr_mcycle")
  // BoringUtils.addSource(instr_cnt, "csr_minstret")

  // todo: add CSR in the future
  if (Settings.Difftest) {
    val dt_ae = Module(new DifftestArchEvent)
    dt_ae.io.clock        := clock
    dt_ae.io.coreid       := 0.U
    dt_ae.io.intrNO       := 0.U
    dt_ae.io.cause        := 0.U
    dt_ae.io.exceptionPC  := 0.U

    val dt_cs = Module(new DifftestCSRState)
    dt_cs.io.clock          := clock
    dt_cs.io.coreid         := 0.U
    dt_cs.io.priviledgeMode := 3.U  // Machine mode
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
  }
}
