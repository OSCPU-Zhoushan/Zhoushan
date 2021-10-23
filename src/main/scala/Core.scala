/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import difftest._

class Core extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val imem = new CacheBusWithUserIO
    val dmem = new CacheBusIO
  })

  val stall = WireInit(false.B)
  val flush = WireInit(false.B)

  /* ----- Stage 1 - Instruction Fetch (IF) ------ */

  val fetch = Module(new InstFetch)
  io.imem <> fetch.io.imem

  val if_id = Module(new PipelineReg(new InstPacket))
  if_id.io.in <> fetch.io.out
  if_id.io.stall := stall
  if_id.io.flush := flush

  /* ----- Stage 2 - Instruction Decode (ID) ----- */

  val decode = Module(new Decode)
  decode.io.in <> if_id.io.out

  val rf = Module(new RegFile)
  rf.io.rs1_addr := decode.io.out.rs1_addr
  rf.io.rs2_addr := decode.io.out.rs2_addr

  val id_ex = Module(new PipelineReg(new ExPacket))
  id_ex.io.in.uop := decode.io.out
  id_ex.io.in.rs1_data := rf.io.rs1_data
  id_ex.io.in.rs2_data := rf.io.rs2_data
  id_ex.io.stall := stall
  id_ex.io.flush := flush

  /* ----- Stage 3 - Execution (EX) -------------- */

  val execution = Module(new Execution)
  execution.io.uop := id_ex.io.out.uop

  val ex_rs1_data = WireInit(UInt(64.W), 0.U)
  val ex_rs2_data = WireInit(UInt(64.W), 0.U)

  execution.io.rs1_data := ex_rs1_data
  execution.io.rs2_data := ex_rs2_data

  val crossbar1to2 = Module(new CacheBusCrossbar1to2(new CacheBusIO))
  crossbar1to2.io.in <> execution.io.dmem

  val cb1to2_addr = crossbar1to2.io.in.req.bits.addr
  val cb1to2_to_1 = (cb1to2_addr >= ClintAddrBase.U) &&
                    (cb1to2_addr < ClintAddrBase.U + ClintAddrSize.U)
  crossbar1to2.io.to_1 := cb1to2_to_1

  val clint = Module(new Clint)
  crossbar1to2.io.out(0) <> io.dmem
  crossbar1to2.io.out(1) <> clint.io.in

  val ex_cm = Module(new PipelineReg(new CommitPacket))
  ex_cm.io.in.uop := execution.io.uop
  ex_cm.io.in.rd_data := execution.io.rd_data
  ex_cm.io.stall := false.B
  ex_cm.io.flush := execution.io.busy

  /* ----- Stage 4 - Commit (CM) ----------------- */

  val cm = WireInit(0.U.asTypeOf(new MicroOp))
  cm := ex_cm.io.out.uop

  rf.io.rd_addr := execution.io.uop.rd_addr
  rf.io.rd_data := execution.io.rd_data
  rf.io.rd_en := execution.io.uop.valid && execution.io.uop.rd_en

  /* ----- Forwarding Unit ----------------------- */

  val ex_rs1_from_cm = cm.valid && cm.rd_en &&
                      (cm.rd_addr =/= 0.U) &&
                      (cm.rd_addr === id_ex.io.out.uop.rs1_addr)
  ex_rs1_data := Mux(ex_rs1_from_cm, ex_cm.io.out.rd_data, id_ex.io.out.rs1_data)
  val ex_rs2_from_cm = cm.valid && cm.rd_en &&
                      (cm.rd_addr =/= 0.U) &&
                      (cm.rd_addr === id_ex.io.out.uop.rs2_addr)
  ex_rs2_data := Mux(ex_rs2_from_cm, ex_cm.io.out.rd_data, id_ex.io.out.rs2_data)

  /* ----- Pipeline Control Signals -------------- */

  fetch.io.jmp_packet := execution.io.jmp_packet
  fetch.io.stall := stall
  flush := execution.io.jmp_packet.jmp
  stall := execution.io.busy

  /* ----- CSR & Difftest ------------------------ */

  val cycle_cnt = RegInit(UInt(64.W), 0.U)
  val instr_cnt = RegInit(UInt(64.W), 0.U)

  BoringUtils.addSource(cycle_cnt, "csr_mcycle")
  BoringUtils.addSource(instr_cnt, "csr_minstret")

  cycle_cnt := cycle_cnt + 1.U
  instr_cnt := instr_cnt + cm.valid.asUInt()

  if (EnableDifftest) {
    val rf_a0 = WireInit(0.U(64.W))
    BoringUtils.addSink(rf_a0, "rf_a0")

    val skip = (cm.inst === Instructions.PUTCH) ||
               (cm.fu_code === s"b${Constant.FU_SYS}".U && cm.inst(31, 20) === Csrs.mcycle)

    val dt_ic = Module(new DifftestInstrCommit)
    dt_ic.io.clock    := clock
    dt_ic.io.coreid   := 0.U
    dt_ic.io.index    := 0.U
    dt_ic.io.valid    := cm.valid
    dt_ic.io.pc       := cm.pc
    dt_ic.io.instr    := cm.inst
    dt_ic.io.skip     := skip
    dt_ic.io.isRVC    := false.B
    dt_ic.io.scFailed := false.B
    dt_ic.io.wen      := cm.rd_en
    dt_ic.io.wdata    := ex_cm.io.out.rd_data
    dt_ic.io.wdest    := cm.rd_addr

    when (dt_ic.io.valid && dt_ic.io.instr === Instructions.PUTCH) {
      printf("%c", rf_a0(7, 0))
    }

    if (DebugCommit) {
      when (cm.valid) {
        printf("%d: [CMUOP] pc=%x inst=%x\n", DebugTimer(), cm.pc, cm.inst)
      }
    }

    val trap = (cm.inst === "h0000006b".U) & cm.valid

    val dt_te = Module(new DifftestTrapEvent)
    dt_te.io.clock    := clock
    dt_te.io.coreid   := 0.U
    dt_te.io.valid    := trap.orR
    dt_te.io.code     := rf_a0(2, 0)
    dt_te.io.pc       := 0.U
    dt_te.io.pc       := cm.pc
    dt_te.io.cycleCnt := cycle_cnt
    dt_te.io.instrCnt := instr_cnt
  }

}
