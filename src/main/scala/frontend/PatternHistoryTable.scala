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

abstract class AbstractPatternHistoryTable extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    val rindex = Vec(FetchWidth, Input(UInt(PhtIndexSize.W)))   // only used in local PHT
    val raddr = Vec(FetchWidth, Input(UInt(PhtAddrSize.W)))
    val rdirect = Vec(FetchWidth, Output(Bool()))
    val windex = Input(UInt(PhtIndexSize.W))                    // only used in local PHT
    val waddr = Input(UInt(PhtAddrSize.W))
    val wen = Input(Bool())
    val wjmp = Input(Bool())
  })
}

class PatternHistoryTableLocal extends AbstractPatternHistoryTable {

  val pht_1 = for (j <- 0 until PhtWidth) yield {
    val pht_1 = SyncReadMem(PhtSize, UInt(1.W), SyncReadMem.WriteFirst)
    pht_1
  }

  val pht_0 = for (j <- 0 until PhtWidth) yield {
    val pht_0 = SyncReadMem(PhtSize, UInt(1.W), SyncReadMem.WriteFirst)
    pht_0
  }

  // read from pht
  for (i <- 0 until FetchWidth) {
    val pht_rdata_1 = WireInit(VecInit(Seq.fill(PhtWidth)(0.U(1.W))))

    // stage 1
    for (j <- 0 until PhtWidth) {
      pht_rdata_1(j) := pht_1(j).read(io.raddr(i))
    }

    // stage 2
    io.rdirect(i) := false.B
    for (j <- 0 until PhtWidth) {
      when (RegNext(io.rindex(i)) === j.U) {
        io.rdirect(i) := pht_rdata_1(j).asBool()
      }
    }
  }

  // write to pht
  val pht_wdata = WireInit(VecInit(Seq.fill(PhtWidth)(0.U(2.W))))
  val pht_wdata_r = WireInit(UInt(2.W), 0.U)  // first read PHT state
  val pht_wdata_w = WireInit(UInt(2.W), 0.U)  // then write PHT state

  // stage 1
  for (j <- 0 until PhtWidth) {
    pht_wdata(j) := Cat(pht_1(j).read(io.waddr), pht_0(j).read(io.waddr))
  }

  // stage 2
  when (RegNext(io.wen)) {
    for (j <- 0 until PhtWidth) {
      when (RegNext(io.windex) === j.U) {
        pht_wdata_r := pht_wdata(j)
      }
    }
  }
  pht_wdata_w := MuxLookup(pht_wdata_r, 0.U, Array(
    0.U -> Mux(RegNext(io.wjmp), 1.U, 0.U),   // strongly not taken
    1.U -> Mux(RegNext(io.wjmp), 2.U, 0.U),   // weakly not taken
    2.U -> Mux(RegNext(io.wjmp), 3.U, 1.U),   // weakly taken
    3.U -> Mux(RegNext(io.wjmp), 3.U, 2.U)    // strongly taken
  ))
  when (RegNext(io.wen)) {
    for (j <- 0 until PhtWidth) {
      when (RegNext(io.windex === j.U)) {
        pht_1(j).write(RegNext(io.waddr), pht_wdata_w(1))
        pht_0(j).write(RegNext(io.waddr), pht_wdata_w(0))
      }
    }
  }

  // sync reset
  when (reset.asBool()) {
    for (i <- 0 until PhtWidth) {
      for (j <- 0 until PhtSize) {
        pht_1(i).write(j.U, 0.U)
        pht_0(i).write(j.U, 0.U)
      }
    }
  }

}

class PatternHistoryTableGlobal extends AbstractPatternHistoryTable {

  val pht_1 = SyncReadMem(PhtSize, UInt(1.W), SyncReadMem.WriteFirst)
  val pht_0 = SyncReadMem(PhtSize, UInt(1.W), SyncReadMem.WriteFirst)

  // read from pht
  for (i <- 0 until FetchWidth) {
    val pht_rdata_1 = WireInit(0.U(2.W))

    // stage 1
    pht_rdata_1 := pht_1.read(io.raddr(i))

    // stage 2
    io.rdirect(i) := pht_rdata_1(1).asBool()
  }

  // write to pht
  val pht_wdata = WireInit(0.U(2.W))
  val pht_wdata_r = WireInit(UInt(2.W), 0.U)  // first read PHT state
  val pht_wdata_w = WireInit(UInt(2.W), 0.U)  // then write PHT state

  // stage 1
  pht_wdata := Cat(pht_1.read(io.waddr), pht_0.read(io.waddr))

  // stage 2
  when (RegNext(io.wen)) {
    pht_wdata_r := pht_wdata
  }
  pht_wdata_w := MuxLookup(pht_wdata_r, 0.U, Array(
    0.U -> Mux(RegNext(io.wjmp), 1.U, 0.U),   // strongly not taken
    1.U -> Mux(RegNext(io.wjmp), 2.U, 0.U),   // weakly not taken
    2.U -> Mux(RegNext(io.wjmp), 3.U, 1.U),   // weakly taken
    3.U -> Mux(RegNext(io.wjmp), 3.U, 2.U)    // strongly taken
  ))
  when (RegNext(io.wen)) {
    pht_1.write(RegNext(io.waddr), pht_wdata_w(1))
    pht_0.write(RegNext(io.waddr), pht_wdata_w(0))
  }

  // sync reset
  when (reset.asBool()) {
    for (i <- 0 until PhtSize) {
      pht_1.write(i.U, 0.U)
      pht_0.write(i.U, 0.U)
    }
  }

}
