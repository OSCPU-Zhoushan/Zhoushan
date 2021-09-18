package zhoushan

import chisel3._
import chisel3.util._

class InstFetch extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val imem = new CacheBusIO
    // JmpPackek defined in MicroOp.scala, used for pc redirection
    val jmp_packet = Input(new JmpPacket)
    val out = Decoupled(new InstPacketVec(FetchWidth))  // to instruction buffer
  })

  val req = io.imem.req
  val resp = io.imem.resp

  val empty = RegInit(false.B)                    // whether IF pipeline is empty
  when (resp.fire()) {
    empty := true.B
  }
  when (req.fire()) {
    empty := false.B
  }

  val mis = io.jmp_packet.mis                     // branch mis-predict
  val mis_pc = Mux(io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.inst_pc + 4.U)

  val reg_mis = RegInit(false.B)                  // store branch mis-predict status
  when (mis && !empty) {
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
  val pc_base = Cat(pc(31, 3), Fill(3, 0.U))      // 64-bit aligned pc_base
  val pc_valid = RegInit("b11".U(2.W))

  val npc_s = pc_base + (4 * FetchWidth).U        // next pc sequential

  val npc_p = bp.io.pred_bpc                      // next pc predicted
  val reg_npc_p = RegInit(0.U(32.W))
  val npc_p_real = Mux(RegNext(!bp.io.pc_en), reg_npc_p, npc_p)

  val pred_br = Cat(bp.io.pred_br.reverse) & Fill(2, bp.io.pred_valid && !mis).asUInt()
  val reg_pred_br = RegInit(UInt(FetchWidth.W), 0.U)
  val pred_br_real = Mux(RegNext(!bp.io.pc_en), reg_pred_br, pred_br)

  when (mis) {
    reg_pred_br := 0.U
  } .elsewhen (!bp.io.pc_en && RegNext(bp.io.pc_en)) {
    reg_npc_p := npc_p
    reg_pred_br := pred_br
  }

  // update pc by npc
  // priority: redirection > branch prediction = sequential pc
  val npc = Mux(mis, mis_pc, Mux(pred_br_real.orR, npc_p_real, npc_s))
  val npc_valid = WireInit("b11".U(2.W))
  when (mis) {
    when (mis_pc(2) === 1.U) {
      npc_valid := "b10".U
    }
  } .elsewhen (pred_br_real.orR) {
    when (npc_p_real(2) === 1.U) {
      npc_valid := "b10".U
    }
  }

  bp.io.pc_en := req.fire()
  bp.io.pc := npc

  val pc_update = mis || req.fire()

  when (pc_update) {
    pc := npc
    pc_valid := npc_valid
  }

  // send the request to I$
  // store pc_base, npc, pc_valid, pred_br info in user field
  // restore the info when resp, and send to instruction buffer
  req.bits.addr  := pc_base
  req.bits.ren   := true.B
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen   := false.B
  req.bits.user  := Cat(pred_br_real, pc_valid, npc, pc_base)
  req.valid      := io.out.ready

  resp.ready := io.out.ready || mis

  val out_vec = io.out.bits.vec

  out_vec(1).pc       := resp.bits.user(31, 0) + 4.U
  out_vec(1).inst     := resp.bits.rdata(63, 32)
  out_vec(1).pred_br  := resp.bits.user(67)
  out_vec(1).pred_bpc := Mux(out_vec(1).pred_br, resp.bits.user(63, 32), 0.U)
  out_vec(1).valid    := !out_vec(0).pred_br && resp.bits.user(65).asBool()

  out_vec(0).pc       := resp.bits.user(31, 0)
  out_vec(0).inst     := resp.bits.rdata(31, 0)
  out_vec(0).pred_br  := resp.bits.user(66) && out_vec(0).valid
  out_vec(0).pred_bpc := Mux(out_vec(0).pred_br, resp.bits.user(63, 32), 0.U)
  out_vec(0).valid    := resp.bits.user(64).asBool()

  io.out.valid        := resp.valid && !mis && !reg_mis && RegNext(!mis)

}
