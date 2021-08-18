package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._

class Csr extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
  })

  val uop = io.uop
  val in1 = io.in1
  val csr_rw = (uop.csr_code === CSR_RW) || (uop.csr_code === CSR_RS) || (uop.csr_code === CSR_RC)

  val addr = uop.inst(31, 20)
  val rdata = Wire(UInt(64.W))
  val ren = csr_rw
  val wdata = Wire(UInt(64.W))
  val wen = csr_rw && (in1 =/= 0.U)

  wdata := MuxLookup(uop.csr_code, 0.U, Array(
    CSR_RW -> in1,
    CSR_RS -> (rdata | in1),
    CSR_RC -> (rdata & ~in1)
  ))

  val mcycle = WireInit(0.U(64.W))
  BoringUtils.addSink(mcycle, "csr_mcycle")

  val minstret = WireInit(0.U(64.W))
  BoringUtils.addSink(minstret, "csr_minstret")

  val csr_map = Map(
    RegMap(Csrs.mcycle, mcycle),
    RegMap(Csrs.minstret, minstret)
  )

  RegMap.access(csr_map, addr, rdata, ren, wdata, wen)

  io.out := rdata
}
