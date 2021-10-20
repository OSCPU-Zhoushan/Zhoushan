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
import zhoushan.RasConstant._

sealed class BtbEntry extends Bundle with BpParameters {
  val tag = UInt(BtbTagSize.W)
  val target = UInt(32.W)
  val ras_type = UInt(2.W)
}

abstract class AbstractBranchTargetBuffer extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    val raddr = Vec(FetchWidth, Input(UInt(BtbAddrSize.W)))
    val ren = Vec(FetchWidth, Input(Bool()))
    val rtag = Vec(FetchWidth, Input(UInt(BtbTagSize.W)))
    val rhit = Vec(FetchWidth, Output(Bool()))
    val rtarget = Vec(FetchWidth, Output(UInt(32.W)))
    val rras_type = Vec(FetchWidth, Output(UInt(2.W)))
    val waddr = Input(UInt(BtbAddrSize.W))
    val wen = Input(Bool())
    val wtag = Input(UInt(BtbTagSize.W))
    val wtarget = Input(UInt(32.W))
    val wras_type = Input(UInt(2.W))
    // debug info
    val wpc = Input(UInt(32.W))
  })
}

class BranchTargetBufferDirectMapped extends AbstractBranchTargetBuffer {

  val btb_tag = SyncReadMem(BtbSize, UInt(BtbTagSize.W), SyncReadMem.WriteFirst)
  val btb_target = SyncReadMem(BtbSize, UInt(32.W), SyncReadMem.WriteFirst)
  val btb_ras_type = SyncReadMem(BtbSize, UInt(2.W), SyncReadMem.WriteFirst)

  val valid = Mem(BtbSize, Bool())

  for (i <- 0 until FetchWidth) {
    val rdata = WireInit(0.U.asTypeOf(new BtbEntry))
    val rvalid = RegInit(false.B)
    io.rhit(i) := false.B
    io.rtarget(i) := 0.U
    io.rras_type(i) := RAS_X
    rdata.tag := btb_tag.read(io.raddr(i))
    rdata.target := btb_target.read(io.raddr(i))
    rdata.ras_type := btb_ras_type.read(io.raddr(i))
    rvalid := valid(io.raddr(i))
    when (rvalid && (rdata.tag === RegNext(io.rtag(i)))) {
      io.rhit(i) := true.B
      io.rtarget(i) := rdata.target
      io.rras_type(i) := rdata.ras_type
    }
  }

  val wentry = Wire(new BtbEntry)
  wentry.tag := io.wtag
  wentry.target := io.wtarget
  wentry.ras_type := io.wras_type
  when (io.wen) {
    btb_tag.write(io.waddr, wentry.tag)
    btb_target.write(io.waddr, wentry.target)
    btb_ras_type.write(io.waddr, wentry.ras_type)
    valid(io.waddr) := true.B
    if (DebugBranchPredictorRas) {
      when (wentry.ras_type =/= RAS_X) {
        printf("%d: [BTB-R] pc=%x ras_type=%x\n", DebugTimer(), io.wpc, io.wras_type)
      }
    }
  }

  // sync reset
  when (reset.asBool()) {
    for (i <- 0 until BtbSize) {
      valid(i) := false.B
      btb_tag.write(i.U, 0.U)
      btb_target.write(i.U, 0.U)
      btb_ras_type.write(i.U, 0.U)
    }
  }

}

class BranchTargetBuffer4WayAssociative extends AbstractBranchTargetBuffer {

  // todo: we need to check hit status before write, otherwise we have multi way for the same addr

  // 4-way associative btb
  val btb_tag = for (i <- 0 until 4) yield {
    val btb_tag = SyncReadMem(BtbSize / 4, UInt(BtbTagSize.W), SyncReadMem.WriteFirst)
    btb_tag
  }
  val btb_target = for (i <- 0 until 4) yield {
    val btb_target = SyncReadMem(BtbSize / 4, UInt(32.W), SyncReadMem.WriteFirst)
    btb_target
  }
  val btb_ras_type = for (i <- 0 until 4) yield {
    val btb_ras_type = SyncReadMem(BtbSize / 4, UInt(2.W), SyncReadMem.WriteFirst)
    btb_ras_type
  }
  val valid = for (i <- 0 until 4) yield {
    val valid = Mem(BtbSize / 4, Bool())
    valid
  }

  // plru0 == 0 --> way 0/1, == 1 --> way 2/3
  val plru0 = RegInit(VecInit(Seq.fill(BtbSize / 4)(0.U)))
  // plru1 == 0 --> way 0,   == 1 --> way 1
  val plru1 = RegInit(VecInit(Seq.fill(BtbSize / 4)(0.U)))
  // plru2 == 0 --> way 2,   == 1 --> way 3
  val plru2 = RegInit(VecInit(Seq.fill(BtbSize / 4)(0.U)))

  def updatePlruTree(idx: UInt, way: UInt) = {
    plru0(idx) := ~way(1)
    when (way(1) === 0.U) {
      plru1(idx) := ~way(0)
    } .otherwise {
      plru2(idx) := ~way(0)
    }
  }

  for (i <- 0 until FetchWidth) {
    val rdata = WireInit(VecInit(Seq.fill(4)(0.U.asTypeOf(new BtbEntry))))
    val rvalid = RegInit(VecInit(Seq.fill(4)(false.B)))
    io.rhit(i) := false.B
    io.rtarget(i) := 0.U
    io.rras_type(i) := RAS_X
    for (j <- 0 until 4) {
      rdata(j).tag := btb_tag(j).read(io.raddr(i))
      rdata(j).target := btb_target(j).read(io.raddr(i))
      rdata(j).ras_type := btb_ras_type(j).read(io.raddr(i))
      rvalid(j) := valid(j)(io.raddr(i))
      when (rvalid(j) && (rdata(j).tag === RegNext(io.rtag(i)))) {
        io.rhit(i) := true.B
        io.rtarget(i) := rdata(j).target
        io.rras_type(i) := rdata(j).ras_type
        when (RegNext(io.ren(i))) {
          updatePlruTree(RegNext(io.raddr(i)), j.U)
          if (DebugBranchPredictorBtb) {
            printf("%d: [BTB-R] addr=%d way=%x\n", DebugTimer(), RegNext(io.raddr(i)), j.U)
          }
        }
      }
    }
  }

  // write to btb: 1. check hit or not; 2. write

  val wentry = Wire(new BtbEntry)
  wentry.tag := io.wtag
  wentry.target := io.wtarget
  wentry.ras_type := io.wras_type
  val replace_way = Cat(plru0(io.waddr),
                        Mux(plru0(io.waddr) === 0.U, plru1(io.waddr), plru2(io.waddr)))

  val w_rdata = WireInit(VecInit(Seq.fill(4)(0.U.asTypeOf(new BtbEntry))))
  val w_rvalid = RegInit(VecInit(Seq.fill(4)(false.B)))
  val w_hit = WireInit(false.B)
  val w_way = WireInit(0.U(replace_way.getWidth.W))

  for (j <- 0 until 4) {
    w_rdata(j).tag := btb_tag(j).read(io.waddr)
    // w_rdata(j).target := btb_target(j).read(io.waddr)  // unused, skip
    w_rdata(j).ras_type := btb_ras_type(j).read(io.waddr)
    w_rvalid(j) := valid(j)(io.waddr)
    when (w_rvalid(j) && (w_rdata(j).tag === RegNext(io.wtag))) {
      w_hit := true.B
      w_way := j.U
    }
  }

  when (RegNext(io.wen)) {
    when (w_hit) {
      for (j <- 0 until 4) {
        when (w_way === j.U) {
          btb_tag(j).write(RegNext(io.waddr), RegNext(wentry.tag))
          btb_target(j).write(RegNext(io.waddr), RegNext(wentry.target))
          btb_ras_type(j).write(RegNext(io.waddr), RegNext(wentry.ras_type))
          updatePlruTree(RegNext(io.waddr), j.U)
          if (DebugBranchPredictorBtb) {
            printf("%d: [BTB-W] addr=%d way=%x w_hit=1\n", DebugTimer(), RegNext(io.waddr), j.U)
          }
        }
      }
    } .otherwise {
      for (j <- 0 until 4) {
        when (replace_way === j.U) {
          btb_tag(j).write(RegNext(io.waddr), RegNext(wentry.tag))
          btb_target(j).write(RegNext(io.waddr), RegNext(wentry.target))
          btb_ras_type(j).write(RegNext(io.waddr), RegNext(wentry.ras_type))
          valid(j)(RegNext(io.waddr)) := true.B
          updatePlruTree(RegNext(io.waddr), j.U)
          if (DebugBranchPredictorBtb) {
            printf("%d: [BTB-W] addr=%d way=%x w_hit=0\n", DebugTimer(), RegNext(io.waddr), j.U)
          }
        }
      }
    }
  }

  when (io.wen) {
    if (DebugBranchPredictorRas) {
      when (wentry.ras_type =/= RAS_X) {
        printf("%d: [BTB-R] pc=%x ras_type=%x\n", DebugTimer(), io.wpc, io.wras_type)
      }
    }
  }

  // sync reset
  when (reset.asBool()) {
    for (i <- 0 until 4) {
      for (j <- 0 until BtbSize / 4) {
        btb_tag(i).write(j.U, 0.U)
        btb_target(i).write(j.U, 0.U)
        btb_ras_type(i).write(j.U, 0.U)
        valid(i)(j) := false.B
      }
    }
  }

}
