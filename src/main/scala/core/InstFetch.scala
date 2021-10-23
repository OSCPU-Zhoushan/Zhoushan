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

class InstFetch extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val imem = new CacheBusWithUserIO
    val jmp_packet = Input(new JmpPacket)
    val out = Decoupled(Output(new InstPacket))
  })

  val req = io.imem.req
  val resp = io.imem.resp

  val jmp = io.jmp_packet.jmp
  val jmp_pc = io.jmp_packet.jmp_pc

  val reg_jmp = RegInit(false.B)
  when (jmp) {
    reg_jmp := true.B
  } .elsewhen (resp.fire() && !jmp) {
    reg_jmp := false.B
  }

  val pc_init = ResetPc.U
  val pc = RegInit(pc_init)
  val pc_update = jmp || req.fire()
  val pc_next = Mux(jmp, jmp_pc, pc + 4.U)

  when (pc_update) {
    pc := pc_next
  }

  req.bits.addr  := pc
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen   := false.B
  req.bits.size  := s"b$MEM_WORD".U
  req.bits.user  := pc
  req.valid      := io.out.ready
  resp.ready     := io.out.ready || jmp

  io.out.bits.pc := resp.bits.user(31, 0)
  io.out.bits.inst := Mux(io.out.bits.pc(2),
                          resp.bits.rdata(63, 32),
                          resp.bits.rdata(31, 0))
  io.out.valid := resp.valid && !jmp && !reg_jmp

}
