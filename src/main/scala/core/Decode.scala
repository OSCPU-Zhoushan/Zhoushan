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
import chisel3.util.experimental.decode._

class Decode extends Module {
  val io = IO(new Bundle {
    val in = Input(new InstPacket)
    val out = Output(new MicroOp)
  })

  val inst = io.in.inst
  val uop = WireInit(0.U.asTypeOf(new MicroOp))

  uop.pc := io.in.pc
  uop.inst := inst

  uop.rs1_addr := inst(19, 15)
  uop.rs2_addr := inst(24, 20)
  uop.rd_addr := inst(11, 7)

  val decode_result = decoder(minimizer = EspressoMinimizer,
                              input = inst,
                              truthTable = DecodeConfig.decode_table)

  uop.from_decoder(decode_result, inst(11, 7))

  io.out := Mux(io.in.valid, uop, 0.U.asTypeOf(new MicroOp))
}
