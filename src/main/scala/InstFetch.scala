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
  val s1_pred_br = bp.io.pred_br
  val s1_pred_pc = bp.io.pred_pc
  bp.io.pc := s1_pc

  /* ----- IF Stage 2 ---------------- */

  val s2_pc = RegInit(pc_init)
  val s2_pc_valid = RegInit(false.B)

  val s2_pred_br = RegInit(false.B)
  val s2_pred_pc = RegInit(UInt(32.W), 0.U)
  val s2_inst = WireInit(UInt(32.W), 0.U)
  val s2_inst_valid = WireInit(false.B)

  val s1_mis = io.jmp_packet.mis
  val s1_mis_pc = Mux(io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.inst_pc + 4.U)
  val s2_mis = RegInit(false.B)
  val s2_mis_pc = RegInit(UInt(32.W), 0.U)

  /* ----- Pipeline Ctrl Signals ----- */

  val pipeline_valid = WireInit(false.B)
  val pipeline_ready = WireInit(false.B)
  val pipeline_fire  = pipeline_valid && pipeline_ready

  when (s1_mis && !resp.fire()) {
    s2_mis := true.B
    s2_mis_pc := s1_mis_pc
  }

  // handshake signals with I cache

  req.bits.addr := s1_pc
  req.bits.ren := true.B
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen := false.B
  req.valid := !stall && !s1_mis

  resp.ready := !stall

  // update pc when req.fire()

  when (req.fire() || s1_mis) {
    s2_pc := s1_pc
    s2_pred_br := s1_pred_br
    s2_pred_pc := s1_pred_pc
    s2_pc_valid := true.B
    when (s1_mis) {
      s1_pc := s1_mis_pc
      s2_pc_valid := false.B
    } .elsewhen (s2_mis) {
      s1_pc := s2_mis_pc
      s2_pc_valid := false.B
      s2_mis := false.B
    } .otherwise {
      s1_pc := s1_pred_pc
    }
  }

  // update inst when resp.fire()

  s2_inst_valid := false.B
  when (resp.fire()) {
    s2_inst := Mux(s2_pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
    s2_inst_valid := !s2_mis && s2_pc_valid
  }

  io.out.pc := s2_pc
  io.out.inst := s2_inst
  io.out.valid := s2_inst_valid

  io.out.pred_br := s2_pred_br
  io.out.pred_pc := s2_pred_pc

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
