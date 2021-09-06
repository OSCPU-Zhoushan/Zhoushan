package zhoushan

import chisel3._
import chisel3.util._

class InstFetch extends Module {
  val io = IO(new Bundle {
    val imem = new CacheBusIO
    val jmp_packet = Input(new JmpPacket)
    val out = Decoupled(new InstPacketVec(1))
  })

  val req = io.imem.req
  val resp = io.imem.resp

  val mis = io.jmp_packet.mis
  val mis_pc = Mux(io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.inst_pc + 4.U)

  val reg_mis = RegInit(false.B)
  when (mis) {
    reg_mis := true.B
  } .elsewhen (resp.fire() && !mis) {
    reg_mis := false.B
  } .elsewhen (RegNext(resp.fire() && !req.fire() && mis)) {
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

  req.bits.addr  := pc
  req.bits.ren   := true.B
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen   := false.B
  req.bits.user  := Cat(bp.io.pred_valid && bp.io.pred_br && !mis, npc, pc)
  req.valid      := io.out.ready

  resp.ready := io.out.ready || mis

  io.out.bits.vec(0).bits.pc      := resp.bits.user(31, 0)
  io.out.bits.vec(0).bits.inst    := Mux(io.out.bits.vec(0).bits.pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
  io.out.bits.vec(0).bits.pred_br := resp.bits.user(64)
  io.out.bits.vec(0).bits.pred_pc := resp.bits.user(63, 32)
  io.out.bits.vec(0).valid        := resp.valid && !mis && !reg_mis
  io.out.valid                    := resp.valid && !mis && !reg_mis

}
