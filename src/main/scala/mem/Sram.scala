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
import chisel3.experimental._

// S011HD1P_X32Y2D128 from SoC team
trait SramParameters {
  val SramAddrWidth = 6
  val SramDepth = 64
  val SramDataWidth = 128
}

// ref: https://github.com/OSCPU/ysyxSoC
class S011HD1P_X32Y2D128 extends ExtModule with HasExtModuleResource with SramParameters {
  val CLK = IO(Input(Clock()))
  val CEN = IO(Input(Bool()))
  val WEN = IO(Input(Bool()))
  val A = IO(Input(UInt(SramAddrWidth.W)))
  val D = IO(Input(UInt(SramDataWidth.W)))
  val Q = IO(Output(UInt(SramDataWidth.W)))

  addResource("/vsrc/S011HD1P_X32Y2D128.v")
}

class Sram(id: Int) extends Module with SramParameters {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val wen = Input(Bool())
    val addr = Input(UInt(SramAddrWidth.W))
    val wdata = Input(UInt(SramDataWidth.W))
    val rdata = Output(UInt(SramDataWidth.W))
  })

  val sram = Module(new S011HD1P_X32Y2D128)
  sram.CLK := clock
  sram.CEN := !io.en
  sram.WEN := !io.wen
  sram.A := io.addr
  sram.D := io.wdata
  io.rdata := sram.Q

  if (ZhoushanConfig.DebugSram) {
    when (io.en) {
      when (io.wen) {
        printf("%d: [SRAM %d] addr=%x wdata=%x\n", DebugTimer(), id.U, io.addr, io.wdata)
      } .otherwise {
        when (id.U >= 20.U) {
          printf("%d: [SRAM %d] addr=%x rdata=%x\n", DebugTimer(), id.U, io.addr, io.rdata)
        }
      }
    }
  }
}
