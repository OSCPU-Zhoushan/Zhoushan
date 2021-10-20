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

object RasConstant {
  val RAS_X             = 0.asUInt(2.W)
  val RAS_PUSH          = 1.asUInt(2.W)
  val RAS_POP           = 2.asUInt(2.W)
  val RAS_POP_THEN_PUSH = 3.asUInt(2.W)

  def isRasPush(x: UInt): Bool = x(0) === 1.U
  def isRasPop(x: UInt): Bool = x(1) === 1.U
}

class JmpPacket extends Bundle {
  val valid    = Bool()
  val inst_pc  = UInt(32.W)
  val jmp      = Bool()
  val jmp_pc   = UInt(32.W)
  val mis      = Bool()
  val sys      = Bool()
  val ras_type = UInt(2.W)
  // debug info
  val pred_br  = Bool()
  val pred_bpc = UInt(32.W)
}

class InstPacket extends Bundle {
  val pc       = UInt(32.W)
  val inst     = UInt(32.W)
  val pred_br  = Bool()
  val pred_bpc = UInt(32.W)
  val valid    = Bool()
}

class InstPacketVec(vec_width: Int) extends Bundle with ZhoushanConfig {
  val vec = Vec(vec_width, Output(new InstPacket))
  override def cloneType = (new InstPacketVec(vec_width)).asInstanceOf[this.type]
}

class MicroOpVec(vec_width: Int) extends Bundle {
  val vec = Vec(vec_width, Output(new MicroOp))
  override def cloneType = (new MicroOpVec(vec_width)).asInstanceOf[this.type]
}

class ExCommitPacket extends Bundle {
  val store_valid = Bool()
  val mmio        = Bool()
  val jmp_valid   = Bool()
  val jmp         = Bool()
  val jmp_pc      = UInt(32.W)
  val mis         = Bool()
  val rd_data     = UInt(64.W)
}
