package zhoushan

import chisel3._
import chisel3.util._

class BrPredictorIO extends Bundle {
  // from IF stage
  val pc = Input(UInt(32.W))
  // from EX stage
  val jmp_packet = Input(new JmpPacket)
  // prediction result
  val pred_br = Output(Bool())
  val pred_pc = Output(UInt(32.W))
}

trait BpParameters {
  val BhtWidth = 6
  val BhtSize = 64
  val BhtAddrSize = log2Up(BhtSize)     // 6
  val PhtWidth = 8
  val PhtIndexSize = log2Up(PhtWidth)   // 3 
  val PhtSize = 256
  val PhtAddrSize = log2Up(PhtSize)     // 8
  val BtbSize = 64
  val BtbAddrSize = log2Up(BtbSize)     // 6
  val BtbTagSize = 8
}

class PatternHistoryTable extends Module with BpParameters{
  val io = IO(new Bundle {
    val rindex = Input(UInt(PhtIndexSize.W))
    val raddr = Input(UInt(PhtAddrSize.W))
    val rdirect = Output(Bool())
    val windex = Input(UInt(PhtIndexSize.W))
    val waddr = Input(UInt(PhtAddrSize.W))
    val wen = Input(Bool())
    val wjmp = Input(Bool())
  })

  def defaultState() : UInt = 1.U(2.W)
  val pht = RegInit(VecInit(Seq.fill(PhtWidth)(VecInit(Seq.fill(PhtSize)(defaultState())))))

  io.rdirect := pht(io.rindex)(io.raddr)(1).asBool()

  val pht_wstate = pht(io.windex)(io.waddr)
  when (io.wen) {
    pht_wstate := MuxLookup(pht_wstate, defaultState(), Array(
      0.U -> Mux(io.wjmp, 1.U, 0.U),   // strongly not taken
      1.U -> Mux(io.wjmp, 2.U, 0.U),   // weakly not taken
      2.U -> Mux(io.wjmp, 3.U, 1.U),   // weakly taken
      3.U -> Mux(io.wjmp, 3.U, 2.U)    // strongly taken
    ))
  }

}

class BranchTargetBuffer extends Module with BpParameters{
  val io = IO(new Bundle {
    val raddr = Input(UInt(BtbAddrSize.W))
    val rtag = Input(UInt(BtbTagSize.W))
    val rhit = Output(Bool())
    val rtarget = Output(UInt(32.W))
    val waddr = Input(UInt(BtbAddrSize.W))
    val wen = Input(Bool())
    val wtag = Input(UInt(BtbTagSize.W))
    val wtarget = Input(UInt(32.W))
  })

  def btbEntry() = new Bundle {
    val valid = Bool()
    val tag = UInt(BtbTagSize.W)
    val target = UInt(32.W)
  }

  val btb = RegInit(VecInit(Seq.fill(BtbSize)(0.U.asTypeOf(btbEntry()))))

  val rdata = btb(io.raddr)
  io.rhit := rdata.valid && (rdata.tag === io.rtag)
  io.rtarget := rdata.target

  when (io.wen) {
    btb(io.waddr).valid := true.B
    btb(io.waddr).tag := io.wtag
    btb(io.waddr).target := io.wtarget
  }

}

class BrPredictor extends Module with BpParameters {
  val io = IO(new BrPredictorIO)

  val pc = io.pc
  val npc = io.pc + 4.U
  val jmp_packet = io.jmp_packet

  val pred_br = WireInit(false.B)
  val pred_pc = WireInit(0.U(32.W))

  // BHT definition
  val bht = RegInit(VecInit(Seq.fill(BhtSize)(0.U(BhtWidth.W))))
  def bhtAddr(x: UInt) : UInt = x(1 + BhtAddrSize, 2)

  // BHT read logic
  val bht_raddr = bhtAddr(pc)
  val bht_rdata = bht(bht_raddr)

  // BHT update logic
  val bht_waddr = bhtAddr(jmp_packet.inst_pc)
  val bht_wrdata = bht(bht_waddr)
  when (jmp_packet.valid) {
    bht(bht_waddr) := Cat(jmp_packet.jmp.asUInt(), bht_wrdata(BhtWidth - 1, 1))
  }

  // PHT definition
  val pht = Module(new PatternHistoryTable)
  def phtAddr(bht_data: UInt, x: UInt) : UInt = bht_data ^ x(1 + BhtWidth, 2)
  def phtIndex(x: UInt) : UInt = x(7 + PhtIndexSize, 8)

  // PHT read logic
  pht.io.raddr := phtAddr(bht_rdata, pc)
  pht.io.rindex := phtIndex(pc)
  val pht_rdirect = pht.io.rdirect

  // PHT update logic
  pht.io.waddr := phtAddr(bht_wrdata, jmp_packet.inst_pc)
  pht.io.windex := phtIndex(jmp_packet.inst_pc)
  pht.io.wen := jmp_packet.valid
  pht.io.wjmp := jmp_packet.jmp

  // BTB definition (direct-mapped)
  val btb = Module(new BranchTargetBuffer)
  def btbAddr(x: UInt) : UInt = x(1 + BtbAddrSize, 2)
  def btbTag(x: UInt) : UInt = x(1 + BtbAddrSize + BtbTagSize, 2 + BtbAddrSize)

  // BTB read logic
  btb.io.raddr := btbAddr(pc)
  btb.io.rtag := btbTag(pc)
  val btb_rhit = btb.io.rhit
  val btb_rtarget = btb.io.rtarget

  // BTB update logic
  btb.io.waddr := btbAddr(jmp_packet.inst_pc)
  btb.io.wen := jmp_packet.valid && jmp_packet.jmp
  btb.io.wtag := btbTag(jmp_packet.inst_pc)
  btb.io.wtarget := jmp_packet.jmp_pc

  when (jmp_packet.valid && jmp_packet.mis) {
    pred_br := false.B
    pred_pc := Mux(jmp_packet.jmp, jmp_packet.jmp_pc, jmp_packet.inst_pc + 4.U)
  } .otherwise {
    when (pht_rdirect) {
      pred_br := btb_rhit   // equivalent to Mux(btb_rhit, pht_rdirect, false.B)
      pred_pc := Mux(btb_rhit, btb_rtarget, npc)
    } .otherwise {
      pred_br := false.B
      pred_pc := npc
    }
  }

  io.pred_br := pred_br
  io.pred_pc := pred_pc

}
