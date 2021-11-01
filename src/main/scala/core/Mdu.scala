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
import zhoushan.Constant._
import freechips.rocketchip.rocket._

class Mdu extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val busy = Output(Bool())
  })

  val mdu_update = io.uop.valid
  val completed = RegInit(true.B)
  when (mdu_update) {
    completed := false.B
  }

  val uop = HoldUnless(io.uop, mdu_update)
  val in1 = HoldUnless(io.in1, mdu_update)
  val in2 = HoldUnless(io.in2, mdu_update)

  val is_mdu = (uop.fu_code === s"b$FU_MDU".U)

  val s_idle :: s_wait :: Nil = Enum(2)
  val state = RegInit(s_idle)

  val rocket_mdu = Module(new MulDiv(MulDivParams(), 64))
  rocket_mdu.io.req.valid    := uop.valid && (state === s_idle) &&
                                is_mdu && (mdu_update || !completed)
  rocket_mdu.io.req.bits.dw  := !uop.w_type
  rocket_mdu.io.req.bits.fn  := uop.mdu_code
  rocket_mdu.io.req.bits.in1 := in1
  rocket_mdu.io.req.bits.in2 := in2
  rocket_mdu.io.req.bits.tag := 0.U
  rocket_mdu.io.kill         := false.B
  rocket_mdu.io.resp.ready   := true.B

  switch (state) {
    is (s_idle) {
      when (rocket_mdu.io.req.fire()) {
        state := s_wait
      }
    }
    is (s_wait) {
      when (rocket_mdu.io.resp.fire()) {
        completed := true.B
        state := s_idle
      }
    }
  }

  io.out := rocket_mdu.io.resp.bits.data
  io.busy := rocket_mdu.io.req.valid ||
             (state === s_wait && !rocket_mdu.io.resp.fire())
}
