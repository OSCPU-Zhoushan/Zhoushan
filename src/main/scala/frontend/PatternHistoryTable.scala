package zhoushan

import chisel3._
import chisel3.util._

class PatternHistoryTable extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    val rindex = Vec(FetchWidth, Input(UInt(PhtIndexSize.W)))
    val raddr = Vec(FetchWidth, Input(UInt(PhtAddrSize.W)))
    val rdirect = Vec(FetchWidth, Output(Bool()))
    val windex = Input(UInt(PhtIndexSize.W))
    val waddr = Input(UInt(PhtAddrSize.W))
    val wen = Input(Bool())
    val wjmp = Input(Bool())
  })

  val pht = for (j <- 0 until PhtWidth) yield {
    val pht = SyncReadMem(PhtSize, UInt(2.W), SyncReadMem.WriteFirst)
    pht
  }

  // read from pht
  for (i <- 0 until FetchWidth) {
    val pht_rdata = WireInit(VecInit(Seq.fill(PhtWidth)(0.U(2.W))))

    // stage 1
    for (j <- 0 until PhtWidth) {
      pht_rdata(j) := pht(j).read(io.raddr(i))
    }

    // stage 2
    io.rdirect(i) := false.B
    for (j <- 0 until PhtWidth) {
      when (RegNext(io.rindex(i)) === j.U) {
        io.rdirect(i) := pht_rdata(j)(1).asBool()
      }
    }
  }

  // write to pht
  val pht_wdata = WireInit(VecInit(Seq.fill(PhtWidth)(0.U(2.W))))
  val pht_wdata_r = WireInit(UInt(2.W), 0.U)  // first read PHT state
  val pht_wdata_w = WireInit(UInt(2.W), 0.U)  // then write PHT state

  // stage 1
  for (j <- 0 until PhtWidth) {
    pht_wdata(j) := pht(j).read(io.waddr)
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
        pht(j).write(RegNext(io.waddr), pht_wdata_w)
      }
    }
  }

}
