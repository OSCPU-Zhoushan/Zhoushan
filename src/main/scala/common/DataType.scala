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

abstract class Packet extends Bundle {
  def flush() : Unit
}

class InstPacket extends Packet {
  val pc = UInt(32.W)
  val inst = UInt(32.W)
  def flush() : Unit = {
    pc := 0.U
    inst := 0.U
  }
}

class ExPacket extends Packet {
  val uop = new MicroOp
  val rs1_data = UInt(64.W)
  val rs2_data = UInt(64.W)
  def flush() : Unit = {
    uop := 0.U.asTypeOf(new MicroOp)
    rs1_data := 0.U
    rs2_data := 0.U
  }
}

class CommitPacket extends Packet {
  val uop = new MicroOp
  val rd_data = UInt(64.W)
  def flush() : Unit = {
    uop := 0.U.asTypeOf(new MicroOp)
    rd_data := 0.U
  }
}

class PipelineReg[T <: Packet](packet: T) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(Output(packet)))
    val out = Decoupled(Output(packet))
    val flush = Input(Bool())
  })

  val reg = RegInit(packet, 0.U.asTypeOf(packet))
  val reg_valid = RegInit(false.B)

  when (io.flush) {
    reg.flush()
  } .elsewhen (io.out.ready) {
    reg := io.in.bits
    reg_valid := io.in.valid
  }

  io.out.bits := reg
  io.out.valid := io.out.ready && reg_valid
  io.in.ready := io.out.ready
}

class JmpPacket extends Bundle {
  val jmp      = Bool()
  val jmp_pc   = UInt(32.W)
}
