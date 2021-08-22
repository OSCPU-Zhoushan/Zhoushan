package zhoushan

import chisel3._
import chisel3.util._

class RAMHelper extends BlackBox {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val en = Input(Bool())
    val rIdx = Input(UInt(64.W))
    val rdata = Output(UInt(64.W))
    val wIdx = Input(UInt(64.W))
    val wdata = Input(UInt(64.W))
    val wmask = Input(UInt(64.W))
    val wen = Input(Bool())
  })
}

class RomIO extends Bundle {
  val en = Input(Bool())
  val addr = Input(UInt(64.W))
  val rdata = Output(UInt(64.W))
}

class RamIO extends RomIO {
  val wdata = Input(UInt(64.W))
  val wmask = Input(UInt(64.W))
  val wen = Input(Bool())
}

class ram_2r1w extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val imem_en = Input(Bool())
    val imem_addr = Input(UInt(64.W))
    val imem_data = Output(UInt(32.W))
    val dmem_en = Input(Bool())
    val dmem_addr = Input(UInt(64.W))
    val dmem_rdata = Output(UInt(64.W))
    val dmem_wdata = Input(UInt(64.W))
    val dmem_wmask = Input(UInt(64.W))
    val dmem_wen = Input(Bool())
  })
  addResource("/vsrc/ram_2r1w.v")
}

class Ram2r1w extends Module {
  val io = IO(new Bundle {
    val imem = new RomIO
    val dmem = new RamIO
  })
  val mem = Module(new ram_2r1w)
  mem.io.clk        := clock
  mem.io.imem_en    := io.imem.en
  mem.io.imem_addr  := io.imem.addr
  io.imem.rdata     := mem.io.imem_data
  mem.io.dmem_en    := io.dmem.en
  mem.io.dmem_addr  := io.dmem.addr
  io.dmem.rdata     := mem.io.dmem_rdata
  mem.io.dmem_wdata := io.dmem.wdata
  mem.io.dmem_wmask := io.dmem.wmask
  mem.io.dmem_wen   := io.dmem.wen
}

class Ram1r1w extends Module {
  val io = IO(new Bundle {
    val dmem = new RamIO
  })
  val mem = Module(new ram_2r1w)
  mem.io.clk        := clock
  mem.io.imem_en    := false.B
  mem.io.imem_addr  := 0.U
  mem.io.dmem_en    := io.dmem.en
  mem.io.dmem_addr  := io.dmem.addr
  io.dmem.rdata     := mem.io.dmem_rdata
  mem.io.dmem_wdata := io.dmem.wdata
  mem.io.dmem_wmask := io.dmem.wmask
  mem.io.dmem_wen   := io.dmem.wen
}

class Rom1r extends Module {
  val io = IO(new Bundle {
    val imem = new RomIO
  })
  val mem = Module(new ram_2r1w)
  mem.io.clk        := clock
  mem.io.imem_en    := io.imem.en
  mem.io.imem_addr  := io.imem.addr
  io.imem.rdata     := mem.io.imem_data
  mem.io.dmem_en    := false.B
  mem.io.dmem_addr  := 0.U
  mem.io.dmem_wdata := 0.U
  mem.io.dmem_wmask := 0.U
  mem.io.dmem_wen   := false.B
}
