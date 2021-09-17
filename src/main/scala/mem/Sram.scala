package zhoushan

import chisel3._
import chisel3.util._

// S011HD1P_X32Y2D128 from SoC team
trait SramParameters {
  val SramAddrWidth = 6
  val SramDepth = 64
  val SramDataWidth = 128
}

// ref: https://github.com/OSCPU/ysyxSoC
class S011HD1P_X32Y2D128 extends BlackBox with HasBlackBoxResource with SramParameters {
  val io = IO(new Bundle {
    val CLK = Input(Clock())
    val CEN = Input(Bool())
    val WEN = Input(Bool())
    val A = Input(UInt(SramAddrWidth.W))
    val D = Input(UInt(SramDataWidth.W))
    val Q = Output(UInt(SramDataWidth.W))
  })
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
  sram.io.CLK := clock
  sram.io.CEN := !io.en
  sram.io.WEN := !io.wen
  sram.io.A := io.addr
  sram.io.D := io.wdata
  io.rdata := sram.io.Q

  if (ZhoushanConfig.DebugMsgSram) {
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
