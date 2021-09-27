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

  val btb = SyncReadMem(BtbSize, new BtbEntry, SyncReadMem.WriteFirst)

  val valid = RegInit(VecInit(Seq.fill(BtbSize)(false.B)))

  for (i <- 0 until FetchWidth) {
    val rdata = WireInit(0.U.asTypeOf(new BtbEntry))
    val rvalid = RegInit(false.B)
    io.rhit(i) := false.B
    io.rtarget(i) := 0.U
    io.rras_type(i) := RAS_X
    rdata := btb.read(io.raddr(i))
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
    btb.write(io.waddr, wentry)
    valid(io.waddr) := true.B
    if (DebugBranchPredictorRas) {
      when (wentry.ras_type =/= RAS_X) {
        printf("%d: [BTB-R] pc=%x ras_type=%x\n", DebugTimer(), io.wpc, io.wras_type)
      }
    }
  }

}

class BranchTargetBuffer4WayAssociative extends AbstractBranchTargetBuffer {

  // todo: we need to check hit status before write, otherwise we have multi way for the same addr

  // 4-way associative btb
  val btb = for (i <- 0 until 4) yield {
    val btb = SyncReadMem(BtbSize / 4, new BtbEntry, SyncReadMem.WriteFirst)
    btb
  }

  val valid = RegInit(VecInit(Seq.fill(4)(VecInit(Seq.fill(BtbSize / 4)(false.B)))))

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
      rdata(j) := btb(j).read(io.raddr(i))
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
    w_rdata(j) := btb(j).read(io.waddr)
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
          btb(j).write(RegNext(io.waddr), RegNext(wentry))
          updatePlruTree(RegNext(io.waddr), j.U)
          if (DebugBranchPredictorBtb) {
            printf("%d: [BTB-W] addr=%d way=%x w_hit=1\n", DebugTimer(), RegNext(io.waddr), j.U)
          }
        }
      }
    } .otherwise {
      for (j <- 0 until 4) {
        when (replace_way === j.U) {
          btb(j).write(RegNext(io.waddr), RegNext(wentry))
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

}
