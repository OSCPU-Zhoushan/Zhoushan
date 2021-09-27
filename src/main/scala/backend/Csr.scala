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

class CsrMip extends CsrSpecial {
  val addr = Csrs.mip
  val romask = "h080".U(64.W)
  val mtip = WireInit(UInt(1.W), 0.U)
  BoringUtils.addSink(mtip, "csr_mip_mtip")
  def apply(): UInt = Cat(Fill(56, 0.U), mtip, Fill(7, 0.U))(63, 0)
  def apply(x: Int): UInt = if (x == 7) mtip else 0.U
  def access(a: UInt, rdata: UInt, ren: Bool, wdata: UInt,
             wmask: UInt, wen: Bool): Unit = {
    when (addr === a && ren) {
      rdata := 0.U // apply()
    }
    when (addr === a && wen) { }
  }
}

class Csr extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val ecp = Output(new ExCommitPacket)
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

  BoringUtils.addSource(mstatus, "csr_mstatus")
  BoringUtils.addSource(mie, "csr_mie")
  BoringUtils.addSource(mtvec, "csr_mtvec")
  BoringUtils.addSource(mip(7).asBool(), "csr_mip_mtip_intr")

  val mcycle    = WireInit(UInt(64.W), 0.U)
  val minstret  = WireInit(UInt(64.W), 0.U)

  BoringUtils.addSink(mcycle, "csr_mcycle")
  BoringUtils.addSink(minstret, "csr_minstret")

  // CSR write function with side effect

  def mstatusWriteFunction(mstatus: UInt): UInt = {
    def get_mstatus_xs(mstatus: UInt): UInt = mstatus(16, 15)
    def get_mstatus_fs(mstatus: UInt): UInt = mstatus(14, 13)
    val mstatus_sd = ((get_mstatus_xs(mstatus) === "b11".U) || (get_mstatus_fs(mstatus) === "b11".U)).asUInt()
    val mstatus_new = Cat(mstatus_sd, mstatus(62, 0))
    mstatus_new
  }

  // ECALL
  when (csr_code === CSR_ECALL) {
    mepc := uop.pc
    mcause := 11.U  // env call from M-mode
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

  // interrupt
  val intr         = WireInit(Bool(), false.B)
  val intr_mstatus = WireInit(UInt(64.W), "h00001800".U)
  val intr_mepc    = WireInit(UInt(64.W), 0.U)
  val intr_mcause  = WireInit(UInt(64.W), 0.U)

  BoringUtils.addSink(intr, "intr")
  BoringUtils.addSink(intr_mstatus, "intr_mstatus")
  BoringUtils.addSink(intr_mepc, "intr_mepc")
  BoringUtils.addSink(intr_mcause, "intr_mcause")

  when (intr) {
    mstatus := intr_mstatus
    mepc := intr_mepc
    mcause := intr_mcause
  }

  // CSR register map

  val csr_map = Map(
    RegMap(Csrs.mhartid , mhartid ),
    RegMap(Csrs.mstatus , mstatus , mstatusWriteFunction),
    RegMap(Csrs.mie     , mie     ),
    RegMap(Csrs.mtvec   , mtvec   ),
    RegMap(Csrs.mscratch, mscratch),
    RegMap(Csrs.mepc    , mepc    ),
    RegMap(Csrs.mcause  , mcause  ),
    // skip mip
    RegMap(Csrs.mcycle  , mcycle  ),
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

  io.ecp.store_valid := false.B
  io.ecp.mmio := false.B
  io.ecp.jmp_valid := csr_jmp
  io.ecp.jmp := csr_jmp
  io.ecp.jmp_pc := csr_jmp_pc
  io.ecp.mis := Mux(csr_jmp, 
                    (uop.pred_br && (csr_jmp_pc =/= uop.pred_bpc)) || !uop.pred_br,
                    uop.pred_br)
  io.ecp.rd_data := rdata

  // difftest for CSR state

  if (ZhoushanConfig.EnableDifftest) {
    val dt_cs = Module(new DifftestCSRState)
    dt_cs.io.clock          := clock
    dt_cs.io.coreid         := 0.U
    dt_cs.io.priviledgeMode := 3.U        // machine mode
    dt_cs.io.mstatus        := mstatus
    dt_cs.io.sstatus        := mstatus & "h80000003000de122".U
    dt_cs.io.mepc           := mepc
    dt_cs.io.sepc           := 0.U
    dt_cs.io.mtval          := 0.U
    dt_cs.io.stval          := 0.U
    dt_cs.io.mtvec          := mtvec
    dt_cs.io.stvec          := 0.U
    dt_cs.io.mcause         := mcause
    dt_cs.io.scause         := 0.U
    dt_cs.io.satp           := 0.U
    dt_cs.io.mip            := 0.U // mip()
    dt_cs.io.mie            := mie
    dt_cs.io.mscratch       := mscratch
    dt_cs.io.sscratch       := 0.U
    dt_cs.io.mideleg        := 0.U
    dt_cs.io.medeleg        := 0.U
  }
}
