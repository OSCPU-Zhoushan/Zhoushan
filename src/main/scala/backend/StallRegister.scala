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

// located at IS stage, before IQ & ROB
class StallRegister extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in = Flipped(Decoupled(new MicroOpVec(DecodeWidth)))
    val out = Decoupled(new MicroOpVec(DecodeWidth))
  })

  val in = io.in.bits.vec

  // store input when out is not ready
  val reg_in = RegInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))
  val reg_in_valid = RegInit(false.B)

  when (io.flush || io.out.fire()) {
    reg_in_valid := false.B
  } .elsewhen (io.in.valid && !io.flush && !io.out.ready && RegNext(io.out.ready)) {
    reg_in := in
    reg_in_valid := true.B
  }

  when (io.out.ready && RegNext(!io.out.ready)) {
    io.out.bits.vec := reg_in
    io.out.valid := reg_in_valid
    reg_in_valid := false.B
  } .otherwise {
    io.out.bits.vec := in
    io.out.valid := io.in.valid
  }
  io.in.ready := io.out.ready
}
