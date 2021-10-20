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

trait AxiParameters {
  val AxiAddrWidth = 32
  val AxiDataWidth = 64
  val AxiIdWidth = 4
  val AxiUserWidth = 1
}

object AxiParameters extends AxiParameters { }

trait AxiId extends Bundle with AxiParameters {
  val id = Output(UInt(AxiIdWidth.W))
}

trait AxiUser extends Bundle with AxiParameters {
  val user = Output(UInt(AxiUserWidth.W))
}

class OscpuSocAxiA extends Bundle with AxiId {
  val addr = Output(UInt(AxiAddrWidth.W))
  val len = Output(UInt(8.W))
  val size = Output(UInt(3.W))
  val burst = Output(UInt(2.W))
}

class AxiA extends OscpuSocAxiA with AxiUser {
  val prot = Output(UInt(3.W))
  val lock = Output(Bool())
  val cache = Output(UInt(4.W))
  val qos = Output(UInt(4.W))
}

class OscpuSocAxiW extends Bundle with AxiParameters {
  val data = Output(UInt(AxiDataWidth.W))
  val strb = Output(UInt((AxiDataWidth / 8).W))
  val last = Output(Bool())
}

class AxiW extends OscpuSocAxiW { }

class OscpuSocAxiB extends Bundle with AxiId {
  val resp = Output(UInt(2.W))
}

class AxiB extends OscpuSocAxiB with AxiUser { }

class OscpuSocAxiR extends Bundle with AxiId {
  val resp = Output(UInt(2.W))
  val data = Output(UInt(AxiDataWidth.W))
  val last = Output(Bool())
}

class AxiR extends OscpuSocAxiR with AxiUser { }

class OscpuSocAxiIO extends Bundle {
  val aw = Decoupled(new OscpuSocAxiA)
  val w = Decoupled(new OscpuSocAxiW)
  val b = Flipped(Decoupled(new OscpuSocAxiB))
  val ar = Decoupled(new OscpuSocAxiA)
  val r = Flipped(Decoupled(new OscpuSocAxiR))
}

class AxiIO extends OscpuSocAxiIO {
  override val aw = Decoupled(new AxiA)
  override val w = Decoupled(new AxiW)
  override val b = Flipped(Decoupled(new AxiB))
  override val ar = Decoupled(new AxiA)
  override val r = Flipped(Decoupled(new AxiR))
}
