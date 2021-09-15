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

abstract class CsrSpecial extends Bundle {
  val addr: UInt
  val romask: UInt
  def apply(): UInt
  def apply(x: Int): UInt
  def access(addr: UInt, rdata: UInt, ren: Bool, wdata: UInt,
             wmask: UInt, wen: Bool): Unit
}

class CsrMip extends Bundle {
  val addr = Csrs.mip
  val romask = "h080".U(64.W)
  val mtip = WireInit(UInt(1.W), 0.U)
  // BoringUtils.addSink(mtip, "csr_mip_mtip")
  def apply(): UInt = Cat(Fill(56, 0.U), mtip, Fill(7, 0.U))(63, 0)
  def apply(x: Int): UInt = if (x == 7) mtip else 0.U
  def access(a: UInt, rdata: UInt, ren: Bool, wdata: UInt,
             wmask: UInt, wen: Bool): Unit = {
    when (addr === a && ren) {
      rdata := apply()
    }
    when (addr === a && wen) {
      
    }
  }
}

class Csr extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val jmp = Output(Bool())
    val jmp_pc = Output(UInt(32.W))
    val intr = Output(Bool())
    val intr_pc = Output(UInt(32.W))
  })

  val uop = io.uop

  val in1 = io.in1
  val csr_code = uop.csr_code
  val csr_rw = (csr_code === CSR_RW) || (csr_code === CSR_RS) || (csr_code === CSR_RC)
  val csr_jmp = WireInit(Bool(), false.B)
  val csr_jmp_pc = WireInit(UInt(32.W), 0.U)

  // CSR register definition

  val mhartid   = RegInit(UInt(64.W), 0.U)
  val mstatus   = RegInit(UInt(64.W), "h00001800".U)
  val mie       = RegInit(UInt(64.W), 0.U)
  val mtvec     = RegInit(UInt(64.W), 0.U)
  val mscratch  = RegInit(UInt(64.W), 0.U)
  val mepc      = RegInit(UInt(64.W), 0.U)
  val mcause    = RegInit(UInt(64.W), 0.U)
  val mip       = new CsrMip

  val mcycle    = WireInit(UInt(64.W), 0.U)
  // BoringUtils.addSink(mcycle, "csr_mcycle")
  val minstret  = WireInit(UInt(64.W), 0.U)
  // BoringUtils.addSink(minstret, "csr_minstret")

  // ECALL
  when (csr_code === CSR_ECALL) {
    mepc := uop.pc
    mcause := 11.U  // Env call from M-mode
    mstatus := Cat(mstatus(63, 8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
    csr_jmp := true.B
    csr_jmp_pc := Cat(mtvec(31, 2), Fill(2, 0.U))
  }

  // MRET
  when (csr_code === CSR_MRET) {
    mstatus := Cat(mstatus(63, 8), 1.U, mstatus(6, 4), mstatus(7), mstatus(2, 0))
    csr_jmp := true.B
    csr_jmp_pc := mepc
  }

  // Interrupt
  val s_idle :: s_wait :: Nil = Enum(2)
  val intr_state = RegInit(s_idle)

  val intr = WireInit(Bool(), false.B)
  val intr_pc = WireInit(UInt(32.W), 0.U)
  val intr_reg = RegInit(Bool(), false.B)
  val intr_no = RegInit(UInt(64.W), 0.U)

  val intr_global_en = (mstatus(3) === 1.U)
  val intr_clint_en = (mie(7) === 1.U)

  intr_reg := false.B
  switch (intr_state) {
    is (s_idle) {
      when (intr_global_en && intr_clint_en) {
        intr_state := s_wait
      }
    }
    is (s_wait) {
      when (uop.valid && mip(7) === 1.U) {
        mepc := uop.pc
        mcause := "h8000000000000007".U
        mstatus := Cat(mstatus(63, 8), mstatus(3), mstatus(6, 4), 0.U, mstatus(2, 0))
        intr := true.B
        intr_pc := Cat(mtvec(31, 2), Fill(2, 0.U))
        intr_reg := true.B
        intr_no := 7.U
        intr_state := s_idle
      }
    }
  }

  // CSR register map

  val csr_map = Map(
    RegMap(Csrs.mhartid,  mhartid ),
    RegMap(Csrs.mstatus,  mstatus ),
    RegMap(Csrs.mie,      mie     ),
    RegMap(Csrs.mtvec,    mtvec   ),
    RegMap(Csrs.mscratch, mscratch),
    RegMap(Csrs.mepc,     mepc    ),
    RegMap(Csrs.mcause,   mcause  ),
    // RegMap(Csrs.mip,      mip     ),
    RegMap(Csrs.mcycle,   mcycle  ),
    RegMap(Csrs.minstret, minstret)
  )

  // CSR register read/write
  
  val addr = uop.inst(31, 20)
  val rdata = WireInit(UInt(64.W), 0.U)
  val ren = csr_rw
  val wdata = Wire(UInt(64.W))
  val wmask = "hffffffff".U
  val wen = csr_rw && (in1 =/= 0.U)

  wdata := MuxLookup(uop.csr_code, 0.U, Array(
    CSR_RW -> in1,
    CSR_RS -> (rdata | in1),
    CSR_RC -> (rdata & ~in1)
  ))

  RegMap.access(csr_map, addr, rdata, ren, wdata, wmask, wen)
  mip.access(addr, rdata, ren, wdata, wmask, wen)

  io.out := rdata
  io.jmp := csr_jmp
  io.jmp_pc := csr_jmp_pc
  io.intr := intr
  io.intr_pc := intr_pc

  // difftest for arch event & CSR state

  if (Settings.Difftest) {
    val dt_ae = Module(new DifftestArchEvent)
    dt_ae.io.clock        := clock
    dt_ae.io.coreid       := 0.U
    dt_ae.io.intrNO       := Mux(intr_reg, intr_no, 0.U)
    dt_ae.io.cause        := 0.U
    dt_ae.io.exceptionPC  := Mux(intr_reg, mepc, 0.U)

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
    dt_cs.io.mip            := mip()
    dt_cs.io.mie            := mie
    dt_cs.io.mscratch       := mscratch
    dt_cs.io.sscratch       := 0.U
    dt_cs.io.mideleg        := 0.U
    dt_cs.io.medeleg        := 0.U
  }
}
