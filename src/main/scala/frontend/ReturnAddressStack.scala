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
import zhoushan.RasConstant._

class ReturnAddressStack extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    val pop_en = Input(Bool())
    val top_pc = Output(UInt(32.W))
    val push_en = Input(Bool())
    val push_pc = Input(UInt(32.W))
    // debug info
    val pop_src_pc = Input(UInt(32.W))
    val push_src_pc = Input(UInt(32.W))
    val mis_inst_pc = Input(UInt(32.W))
  })

  def getAddr(x: UInt): UInt = x(RasPtrSize - 1, 0)
  def getFlag(x: UInt): Bool = x(RasPtrSize).asBool()

  // we chooose ReadFirst to support "pop, then push" (rv unprivileged spec page 22)
  val ras = SyncReadMem(RasSize, UInt(32.W), SyncReadMem.ReadFirst)

  val fp = RegInit(UInt((RasPtrSize + 1).W), 0.U)   // frame pointer (base)
  val sp = RegInit(UInt((RasPtrSize + 1).W), 0.U)   // stack pointer (top)

  val sp_inc = sp + 1.U
  val sp_dec = sp - 1.U

  val is_empty = (fp === sp)
  val is_full = (getAddr(fp) === getAddr(sp_inc)) && (getFlag(fp) =/= getFlag(sp_inc))

  val stack_top_pc = ras.read(sp - 1.U)

  when (io.pop_en && !is_empty) {
    sp := sp_dec
  }
  io.top_pc := Mux(RegNext(io.push_en), RegNext(io.push_pc), stack_top_pc)

  when (io.push_en) {
    ras.write(sp, io.push_pc)
    sp := sp_inc
    when (is_full) {
      fp := fp + 1.U
    }
  }

  if (DebugBranchPredictorRas) {
    when (io.push_en) {
      printf("%d: [RAS-W] fp=%x sp=%x push=%x push_pc=%x src_pc=%x mis_inst_pc=%x\n", DebugTimer(), fp, sp, io.push_en, io.push_pc, io.push_src_pc, io.mis_inst_pc)
    }
    when (io.pop_en) {
      printf("%d: [RAS-R] fp=%x sp=%x pop=%x  pop_pc=%x  src_pc=%x mis_inst_pc=%x\n", DebugTimer(), fp, sp, io.pop_en, io.top_pc, io.pop_src_pc, io.mis_inst_pc)
    }
  }

}
