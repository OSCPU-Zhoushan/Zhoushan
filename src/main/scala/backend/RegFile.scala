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
import chisel3.util.experimental._
import difftest._

class RegFile extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val rs1_addr = Input(UInt(5.W))
    val rs2_addr = Input(UInt(5.W))
    val rs1_data = Output(UInt(64.W))
    val rs2_data = Output(UInt(64.W))
    val rd_addr = Input(UInt(5.W))
    val rd_data = Input(UInt(64.W))
    val rd_en = Input(Bool())
  })

  val rf = Mem(32, UInt(64.W))

  when (io.rd_en && (io.rd_addr =/= 0.U)) {
    rf(io.rd_addr) := io.rd_data;
  }

  io.rs1_data := Mux((io.rs1_addr =/= 0.U), rf(io.rs1_addr), 0.U)
  io.rs2_data := Mux((io.rs2_addr =/= 0.U), rf(io.rs2_addr), 0.U)

  // bypassing logic
  when (io.rd_en) {
    when (io.rd_addr === io.rs1_addr) {
      io.rs1_data := io.rd_data
    }
    when (io.rd_addr === io.rs2_addr) {
      io.rs2_data := io.rd_data
    }
  }

  when (reset.asBool()) {
    for (i <- 0 until PrfSize) {
      rf(i) := 0.U
    }
  }

  if (EnableDifftest) {
    val dt_ar = Module(new DifftestArchIntRegState)
    dt_ar.io.clock  := clock
    dt_ar.io.coreid := 0.U
    dt_ar.io.gpr    := rf

    BoringUtils.addSource(rf(10), "rf_a0")
  }
}
