package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import difftest._
import zhoushan.Constant._

object Csrs {
  val mhartid  = "hf14".U
  val mstatus  = "h300".U
  val mie      = "h304".U
  val mtvec    = "h305".U
  val mscratch = "h340".U
  val mepc     = "h341".U
  val mcause   = "h342".U
  val mip      = "h344".U
  val mcycle   = "hb00".U
  val minstret = "hb02".U
}

class Csr extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val jmp = Output(Bool())
    val jmp_pc = Output(UInt(32.W))
  })

  def zeros(x: Int) : UInt = { Fill(x, 0.U) }

  val uop = io.uop
  val in1 = io.in1
  val csr_rw = (uop.csr_code === CSR_RW) || (uop.csr_code === CSR_RS) || (uop.csr_code === CSR_RC)
  val csr_ecall = (uop.csr_code === CSR_ECALL)
  val csr_mret = (uop.csr_code === CSR_MRET)
  val csr_jmp = csr_ecall || csr_mret
  val csr_jmp_pc = WireInit(UInt(32.W), 0.U)

  // CSR register definition

  val mhartid   = RegInit(UInt(64.W), 0.U)
  val mstatus   = RegInit(UInt(64.W), "h00001800".U)
  val mie       = RegInit(UInt(64.W), 0.U)
  val mtvec     = RegInit(UInt(64.W), 0.U)
  val mscratch  = RegInit(UInt(64.W), 0.U)
  val mepc      = RegInit(UInt(64.W), 0.U)
  val mcause    = RegInit(UInt(64.W), 0.U)
  val mip       = RegInit(UInt(64.W), 0.U)

  val mcycle    = WireInit(UInt(64.W), 0.U)
  BoringUtils.addSink(mcycle, "csr_mcycle")
  val minstret  = WireInit(UInt(64.W), 0.U)
  BoringUtils.addSink(minstret, "csr_minstret")

  // ECALL
  when (csr_ecall) {
    mepc := uop.pc
    mcause := 11.U  // Env call from M-mode
    mstatus := Cat(mstatus(63, 8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
  }

  // MRET
  when (csr_mret) {
    mstatus := Cat(mstatus(63, 8), 1.U, mstatus(6, 4), mstatus(7), mstatus(2, 0))
  }

  csr_jmp_pc := MuxLookup(uop.csr_code, 0.U, Array(
    CSR_ECALL -> Cat(mtvec(31, 2), Fill(2, 0.U)),
    CSR_MRET  -> mepc
  ))

  // CSR register map

  val csr_map = Map(
    RegMap(Csrs.mhartid,  mhartid ),
    RegMap(Csrs.mstatus,  mstatus ),
    RegMap(Csrs.mie,      mie     ),
    RegMap(Csrs.mtvec,    mtvec   ),
    RegMap(Csrs.mscratch, mscratch),
    RegMap(Csrs.mepc,     mepc    ),
    RegMap(Csrs.mcause,   mcause  ),
    RegMap(Csrs.mip,      mip     ),
    RegMap(Csrs.mcycle,   mcycle  ),
    RegMap(Csrs.minstret, minstret)
  )

  // CSR register read/write
  
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

  RegMap.access(csr_map, addr, rdata, ren, wdata, wen)

  io.out := rdata
  io.jmp := csr_jmp
  io.jmp_pc := csr_jmp_pc

  // difftest for CSR state

  val dt_cs = Module(new DifftestCSRState)
  dt_cs.io.clock          := clock
  dt_cs.io.coreid         := 0.U
  dt_cs.io.priviledgeMode := 3.U  // Machine mode
  dt_cs.io.mstatus        := mstatus
  dt_cs.io.sstatus        := 0.U
  dt_cs.io.mepc           := mepc
  dt_cs.io.sepc           := 0.U
  dt_cs.io.mtval          := 0.U
  dt_cs.io.stval          := 0.U
  dt_cs.io.mtvec          := mtvec
  dt_cs.io.stvec          := 0.U
  dt_cs.io.mcause         := mcause
  dt_cs.io.scause         := 0.U
  dt_cs.io.satp           := 0.U
  dt_cs.io.mip            := mip
  dt_cs.io.mie            := mie
  dt_cs.io.mscratch       := mscratch
  dt_cs.io.sscratch       := 0.U
  dt_cs.io.mideleg        := 0.U
  dt_cs.io.medeleg        := 0.U
}
