package zhoushan

import chisel3._
import chisel3.util._

class BrPredictorIO extends Bundle {
  // from IF stage
  val pc = Input(UInt(32.W))
  val inst = Input(UInt(32.W))
  val is_br = Input(Bool())
  // from EX stage
  val jmp_packet = Input(new JmpPacket)
  // prediction result
  val pred_br = Output(Bool())
  val pred_pc = Output(UInt(32.W))
}

class BrPredictor extends Module {
  val io = IO(new BrPredictorIO)

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

  val pc = io.pc
  val npc = io.pc + 4.U
  val inst = io.inst
  val is_br = io.is_br
  val jmp_packet = io.jmp_packet

  val pred_br = WireInit(false.B)
  val pred_pc = WireInit(0.U(32.W))

  // BHT/PHT definition

  def default_state() : UInt = 1.U(2.W)
  val bht = RegInit(VecInit(Seq.fill(BhtSize)(0.U(BhtWidth.W))))
  val pht = RegInit(VecInit(Seq.fill(PhtWidth)(VecInit(Seq.fill(PhtSize)(default_state())))))
  def bht_addr(x: UInt) : UInt = x(1 + BhtAddrSize, 2)
  def pht_addr(bht_data: UInt, x: UInt) : UInt = bht_data ^ x(1 + BhtWidth, 2)
  def pht_index(x: UInt) : UInt = x(7 + PhtIndexSize, 8)

  // BHT/PHT read logic

  val bht_raddr = bht_addr(pc)
  val bht_rdata = bht(bht_raddr)
  val pht_raddr = pht_addr(bht_rdata, pc)
  val pht_rindex = pht_index(pc)
  val pht_rdirect = pht(pht_rindex)(pht_raddr)(1).asBool()

  // BHT/PHT update logic

  val bht_waddr = bht_addr(jmp_packet.inst_pc)
  val bht_wrdata = bht(bht_waddr)
  when (jmp_packet.valid) {
    bht(bht_waddr) := Cat(jmp_packet.jmp.asUInt(), bht_wrdata(BhtWidth - 1, 1))
  }
  val pht_waddr = pht_addr(bht_wrdata, jmp_packet.inst_pc)
  val pht_windex = pht_index(jmp_packet.inst_pc)
  val pht_wstate = pht(pht_windex)(pht_waddr)
  when (jmp_packet.valid) {
    pht_wstate := MuxLookup(pht_wstate, default_state(), Array(
      0.U -> Mux(jmp_packet.jmp, 1.U, 0.U),   // strongly not taken
      1.U -> Mux(jmp_packet.jmp, 2.U, 0.U),   // weakly not taken
      2.U -> Mux(jmp_packet.jmp, 3.U, 1.U),   // weakly taken
      3.U -> Mux(jmp_packet.jmp, 3.U, 2.U)    // strongly taken
    ))
  }

  // BTB definition (direct-mapped)

  def btb_entry() = new Bundle {
    val valid = Bool()
    val tag = UInt(BtbTagSize.W)
    val target = UInt(32.W)
  }
  val btb = RegInit(VecInit(Seq.fill(BtbSize)(0.U.asTypeOf(btb_entry()))))
  def btb_addr(x: UInt) : UInt = x(1 + BtbAddrSize, 2)
  def btb_tag(x: UInt) : UInt = x(1 + BtbAddrSize + BtbTagSize, 2 + BtbAddrSize)

  // BTB read logic

  val btb_raddr = btb_addr(pc)
  val btb_rdata = btb(btb_raddr)
  val btb_rhit = btb_rdata.valid && (btb_rdata.tag === btb_tag(pc))

  // BTB update logic

  val btb_waddr = btb_addr(jmp_packet.inst_pc)
  when (jmp_packet.valid && jmp_packet.jmp) {
    btb(btb_waddr).valid := true.B
    btb(btb_waddr).tag := btb_tag(jmp_packet.inst_pc)
    btb(btb_waddr).target := jmp_packet.jmp_pc
  }

  when (jmp_packet.valid && jmp_packet.mis) {
    pred_br := false.B
    pred_pc := Mux(jmp_packet.jmp, jmp_packet.jmp_pc, jmp_packet.inst_pc + 4.U)
  } .otherwise {
    when (pht_rdirect) {
      pred_br := btb_rhit   // equivalent to Mux(btb_rhit, pht_rdirect, false.B)
      pred_pc := Mux(btb_rhit, btb_rdata.target, npc)
    } .otherwise {
      pred_br := false.B
      pred_pc := npc
    }
  }

  io.pred_br := pred_br
  io.pred_pc := pred_pc

}
