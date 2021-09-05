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

  val mis = io.jmp_packet.mis
  val mis_pc = Mux(io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.inst_pc + 4.U)

  val reg_mis = RegInit(false.B)
  when (mis) {
    reg_mis := true.B
  } .elsewhen (resp.fire() && !mis) {
    reg_mis := false.B
  }

  val bp = Module(new BrPredictor)
  bp.io.jmp_packet <> io.jmp_packet

  val pc_init = "h80000000".U(32.W)
  val pc = RegInit(pc_init)
  val pc_update = mis || req.fire()

  val npc_s = pc + 4.U
  val npc_p = bp.io.pred_pc
  val npc = Mux(mis, mis_pc, Mux(bp.io.pred_valid && bp.io.pred_br, npc_p, npc_s))

  bp.io.pc_en := req.fire()
  bp.io.pc := npc

  when (pc_update) {
    pc := npc
  }

  req.bits.addr := pc
  req.bits.ren := true.B
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen := false.B
  req.bits.user := Cat(bp.io.pred_valid && bp.io.pred_br && !mis, npc, pc)
  req.valid := !stall

  resp.ready := !stall || mis

  io.out.pc := resp.bits.user(31, 0)
  io.out.inst := Mux(io.out.pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
  io.out.pred_br := resp.bits.user(64)
  io.out.pred_pc := resp.bits.user(63, 32)
  io.out.valid := resp.valid && !mis && !reg_mis

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
