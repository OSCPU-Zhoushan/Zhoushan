package zhoushan

import chisel3._
import chisel3.util._

abstract class AbstractInstFetchIO extends Bundle {
  val imem : MemIO
  val jmp_packet = Input(new JmpPacket)
  val stall = Input(Bool())
  val out = Output(new InstPacket)
}

class InstFetchIO extends AbstractInstFetchIO {
  override val imem = new CacheBusIO
}

class InstFetchWithRamHelperIO extends AbstractInstFetchIO {
  override val imem = Flipped(new RomIO)
}

abstract class InstFetchModule extends Module {
  val io : Bundle
}

class InstFetch extends InstFetchModule {
  val io = IO(new InstFetchIO)
  val stall = io.stall

  val req = io.imem.req
  val resp = io.imem.resp

  val pc_init = "h80000000".U(32.W)

  val bp = Module(new BrPredictor)
  bp.io.jmp_packet <> io.jmp_packet

  /* ----- IF Stage 1 ---------------- */

  val s1_pc = RegInit(pc_init)

  val s1_mis = io.jmp_packet.mis
  val s1_mis_pc = Mux(io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.inst_pc + 4.U)

  bp.io.pc := s1_pc

  /* ----- IF Stage 2 ---------------- */

  val s2_pc = RegInit(pc_init)
  val s2_pc_valid = RegInit(false.B)

  val s2_pred_br = WireInit(false.B)
  val s2_pred_pc = WireInit(UInt(32.W), 0.U)
  val s2_reg_pred_br = RegInit(false.B)
  val s2_reg_pred_pc = RegInit(UInt(32.W), 0.U)
  val s2_reg_pred_valid = RegInit(false.B)
  val s2_inst = WireInit(UInt(32.W), 0.U)
  val s2_valid = WireInit(false.B)

  /* ----- Pipeline Ctrl Signals ----- */

  val init = RegInit(true.B)
  when (req.valid) {
    init := false.B
  }

  val pipeline_valid = req.valid
  val pipeline_ready = resp.fire() || init
  val pipeline_fire  = pipeline_valid && pipeline_ready
  val pipeline_clear = s1_mis || bp.io.pred_br

  when (pipeline_clear) {
    s1_pc := Mux(s1_mis, s1_mis_pc, bp.io.pred_pc)
  } .elsewhen (req.fire()) {
    s1_pc := s1_pc + 4.U
  }

  when (pipeline_clear) {
    s2_pc_valid := false.B
  } .elsewhen (pipeline_fire) {
    s2_pc := s1_pc
    s2_pc_valid := !s1_mis
    s2_pred_br := bp.io.pred_br
    s2_pred_pc := bp.io.pred_pc
    s2_reg_pred_valid := false.B
  } .elsewhen (!pipeline_fire && RegNext(pipeline_fire)) {
    s2_reg_pred_br := s2_pred_br
    s2_reg_pred_pc := s2_pred_pc
    s2_reg_pred_valid := true.B
  }

  // handshake signals with I cache

  req.bits.addr := s1_pc
  req.bits.ren := true.B
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen := false.B
  req.valid := !stall && !pipeline_clear

  resp.ready := !stall

  // update inst when resp.fire()

  s2_valid := false.B
  when (resp.fire()) {
    s2_inst := Mux(s2_pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
    s2_valid := !s1_mis && s2_pc_valid
  }

  io.out.pc := s2_pc
  io.out.inst := s2_inst
  io.out.valid := s2_valid

  io.out.pred_br := Mux(s2_reg_pred_valid, s2_reg_pred_br, s2_pred_br)
  io.out.pred_pc := Mux(s2_reg_pred_valid, s2_reg_pred_pc, s2_pred_pc)

}

class InstFetchWithRamHelper extends InstFetchModule {
  val io = IO(new InstFetchWithRamHelperIO)

  val pc_init = "h80000000".U(32.W)
  val pc = RegInit(pc_init)
  val inst = io.imem.rdata(31, 0)

  io.imem.en := true.B
  io.imem.addr := pc.asUInt()

  val bp = Module(new BrPredictor)
  bp.io.pc := pc
  bp.io.jmp_packet <> io.jmp_packet

  val pc_zero_reset = RegInit(true.B) // todo: fix pc reset
  pc_zero_reset := false.B
  pc := Mux(pc_zero_reset, pc_init,
        Mux(io.stall, pc, bp.io.pred_pc))

  io.out.pc := pc
  io.out.inst := inst
  io.out.pred_br := bp.io.pred_br
  io.out.pred_pc := bp.io.pred_pc
  io.out.valid := true.B
}
