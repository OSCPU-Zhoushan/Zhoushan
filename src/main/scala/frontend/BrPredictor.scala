package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.RasConstant._

trait BpParameters {
  val BhtWidth = 6
  val BhtSize = 64
  val BhtAddrSize = log2Up(BhtSize)     // 6
  val PhtWidth = 8
  val PhtIndexSize = log2Up(PhtWidth)   // 3 
  val PhtSize = 64                      // 2 ^ BhtWidth
  val PhtAddrSize = log2Up(PhtSize)     // 6 <- BhtWidth
  val BtbSize = 64
  val BtbAddrSize = log2Up(BtbSize)     // 6
  val BtbTagSize = 8
  val RasSize = 16
  val RasPtrSize = log2Up(RasSize)      // 4
}

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

sealed class BtbEntry extends Bundle with BpParameters {
  val valid = Bool()
  val tag = UInt(BtbTagSize.W)
  val target = UInt(32.W)
  val ras_type = UInt(2.W)
}

class BranchTargetBuffer extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    val raddr = Vec(FetchWidth, Input(UInt(BtbAddrSize.W)))
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

  val btb = SyncReadMem(BtbSize, new BtbEntry, SyncReadMem.WriteFirst)

  val rdata = WireInit(VecInit(Seq.fill(FetchWidth)(0.U.asTypeOf(new BtbEntry))))
  for (i <- 0 until FetchWidth) {
    rdata(i) := btb.read(io.raddr(i))
    io.rhit(i) := rdata(i).valid && (rdata(i).tag === RegNext(io.rtag(i)))
    io.rtarget(i) := rdata(i).target
    io.rras_type(i) := rdata(i).ras_type
  }

  val wentry = Wire(new BtbEntry)
  wentry.valid := true.B
  wentry.tag := io.wtag
  wentry.target := io.wtarget
  wentry.ras_type := io.wras_type
  when (io.wen) {
    btb.write(io.waddr, wentry)
    if (Settings.DebugBranchPredictorRas) {
      when (wentry.ras_type =/= RAS_X) {
        printf("%d: [BTB] pc=%x ras_type=%x\n", DebugTimer(), io.wpc, io.wras_type)
      }
    }
  }

}

class ReturnAddressStack extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    val pop_en = Input(Bool())
    val top_pc = Output(UInt(32.W))
    val push_en = Input(Bool())
    val push_pc = Input(UInt(32.W))
    // debug info
    val pop_src_pc = Input(UInt(32.W))
    val push_src_pc = Input(UInt(32.W))
    val mis_inst_pc = Input(UInt(32.W))
  })

  def getAddr(x: UInt): UInt = x(RasPtrSize - 1, 0)
  def getFlag(x: UInt): Bool = x(RasPtrSize).asBool()

  // we chooose ReadFirst to support "pop, then push" (rv unprivileged spec page 22)
  val ras = SyncReadMem(RasSize, UInt(32.W), SyncReadMem.ReadFirst)

  val fp = RegInit(UInt((RasPtrSize + 1).W), 0.U)   // frame pointer (base)
  val sp = RegInit(UInt((RasPtrSize + 1).W), 0.U)   // stack pointer (top)

  val sp_inc = sp + 1.U
  val sp_dec = sp - 1.U

  val is_empty = (fp === sp)
  val is_full = (getAddr(fp) === getAddr(sp_inc)) && (getFlag(fp) =/= getFlag(sp_inc))

  val stack_top_pc = ras.read(sp - 1.U)

  when (io.pop_en && !is_empty) {
    sp := sp_dec
  }
  io.top_pc := Mux(RegNext(io.push_en), RegNext(io.push_pc), stack_top_pc)
  
  when (io.push_en) {
    ras.write(sp, io.push_pc)
    sp := sp_inc
    when (is_full) {
      fp := fp + 1.U
    }
  }

  if (Settings.DebugBranchPredictorRas) {
    when (io.push_en) {
      printf("%d: [RAS] fp=%x sp=%x push=%x push_pc=%x src_pc=%x mis_inst_pc=%x\n", DebugTimer(), fp, sp, io.push_en, io.push_pc, io.push_src_pc, io.mis_inst_pc)
    }
    when (io.pop_en) {
      printf("%d: [RAS] fp=%x sp=%x pop=%x  pop_pc=%x  src_pc=%x mis_inst_pc=%x\n", DebugTimer(), fp, sp, io.pop_en, io.top_pc, io.pop_src_pc, io.mis_inst_pc)
    }
  }

}

class BrPredictor extends Module with BpParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    // from IF stage
    val pc = Input(UInt(32.W))
    val pc_en = Input(Bool())
    // from EX stage
    val jmp_packet = Input(new JmpPacket)
    // prediction result
    val pred_br = Vec(FetchWidth, Output(Bool()))
    val pred_bpc = Output(UInt(32.W))
    val pred_valid = Output(Bool())
  })

  val pc_base = Cat(io.pc(31, 3), Fill(3, 0.U))
  val pc = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(32.W))))
  for (i <- 0 until FetchWidth) {
    pc(i) := pc_base + (i * 4).U
  }
  val npc = pc_base + (4 * FetchWidth).U

  // todo: currently only support 2-way
  val pc_en = RegInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  pc_en(0) := io.pc_en && (io.pc(2) === 0.U)
  pc_en(1) := io.pc_en

  val jmp_packet = io.jmp_packet

  val pred_br = WireInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  val pred_bpc = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(32.W))))

  // BHT definition
  val bht = RegInit(VecInit(Seq.fill(BhtSize)(0.U(BhtWidth.W))))
  def bhtAddr(x: UInt) : UInt = x(1 + BhtAddrSize, 2)

  // BHT read logic
  val bht_raddr = pc.map(bhtAddr(_))
  val bht_rdata = bht_raddr.map(bht(_))

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
  val pht_rdirect = WireInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  for (i <- 0 until FetchWidth) {
    pht.io.raddr(i) := phtAddr(bht_rdata(i), pc(i))
    pht.io.rindex(i) := phtIndex(pc(i))
    pht_rdirect(i) := pht.io.rdirect(i)
  }

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
  val btb_rhit = WireInit(VecInit(Seq.fill(FetchWidth)(false.B)))
  val btb_rtarget = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(32.W))))
  val btb_rras_type = WireInit(VecInit(Seq.fill(FetchWidth)(0.U(2.W))))
  for (i <- 0 until FetchWidth) {
    btb.io.raddr(i) := btbAddr(pc(i))
    btb.io.rtag(i) := btbTag(pc(i))
    btb_rhit(i) := btb.io.rhit(i)
    btb_rtarget(i) := btb.io.rtarget(i)
    btb_rras_type(i) := btb.io.rras_type(i)
  }

  // BTB update logic
  btb.io.waddr := btbAddr(jmp_packet.inst_pc)
  btb.io.wen := jmp_packet.valid && jmp_packet.jmp
  btb.io.wtag := btbTag(jmp_packet.inst_pc)
  btb.io.wtarget := jmp_packet.jmp_pc
  btb.io.wras_type := jmp_packet.ras_type
  btb.io.wpc := jmp_packet.inst_pc            // debug

  // RAS definition
  val ras = Module(new ReturnAddressStack)

  // RAS push logic
  val ras_push_vec = Cat(btb_rras_type.map(isRasPush(_)).reverse) & Cat(btb_rhit.reverse) & Cat(pht_rdirect.reverse) & Cat(pc_en.reverse)
  val ras_push_idx = PriorityEncoder(ras_push_vec)
  ras.io.push_en := (ras_push_vec.orR && !jmp_packet.mis) || (jmp_packet.mis && isRasPush(jmp_packet.ras_type))
  ras.io.push_pc := 0.U
  ras.io.push_src_pc := RegNext(io.pc)        // debug
  ras.io.mis_inst_pc := jmp_packet.inst_pc    // debug
  for (i <- 0 until FetchWidth) {
    when (ras_push_idx === i.U) {
      ras.io.push_pc := RegNext(pc(i) + 4.U)
    }
  }
  when (jmp_packet.mis && isRasPush(jmp_packet.ras_type)) {
    ras.io.push_pc := jmp_packet.inst_pc + 4.U
  }

  // RAS pop logic
  val ras_pop_vec = Cat(btb_rras_type.map(isRasPop(_)).reverse) & Cat(btb_rhit.reverse) & Cat(pht_rdirect.reverse) & Cat(pc_en.reverse)
  val ras_pop_idx = PriorityEncoder(ras_pop_vec)
  ras.io.pop_en := (ras_pop_vec.orR && !jmp_packet.mis) || (jmp_packet.mis && isRasPop(jmp_packet.ras_type))
  ras.io.pop_src_pc := RegNext(io.pc)         // debug
  val ras_ret_en = Wire(Vec(FetchWidth, Bool()))
  val ras_ret_pc = Wire(Vec(FetchWidth, UInt(32.W)))
  for (i <- 0 until FetchWidth) {
    when (ras_pop_vec.orR && ras_pop_idx === i.U && !jmp_packet.mis) {
      ras_ret_en(i) := true.B
      ras_ret_pc(i) := ras.io.top_pc
    } .otherwise {
      ras_ret_en(i) := false.B
      ras_ret_pc(i) := 0.U
    }
  }

  // update pred results
  for (i <- 0 until FetchWidth) {
    when (jmp_packet.valid && jmp_packet.mis) {
      pred_br(i) := false.B
      pred_bpc(i) := Mux(jmp_packet.jmp, jmp_packet.jmp_pc, jmp_packet.inst_pc + 4.U)
    } .otherwise {
      when (ras_ret_en(i)) {
        pred_br(i) := true.B
        pred_bpc(i) := ras_ret_pc(i)
      } .elsewhen (pht_rdirect(i)) {
        pred_br(i) := btb_rhit(i)   // equivalent to Mux(btb_rhit, pht_rdirect, false.B)
        pred_bpc(i) := Mux(btb_rhit(i), btb_rtarget(i), RegNext(npc))
      } .otherwise {
        pred_br(i) := false.B
        pred_bpc(i) := RegNext(npc)
      }
    }
  }

  for (i <- 0 until FetchWidth) {
    io.pred_br(i) := Mux(pc_en(i), pred_br(i), false.B)
  }
  io.pred_bpc := MuxLookup(Cat(pred_br.reverse), 0.U, Array(
    "b11".U -> pred_bpc(0),
    "b01".U -> pred_bpc(0),
    "b10".U -> pred_bpc(1)
  ))
  io.pred_valid := RegNext(io.pc_en)

}
