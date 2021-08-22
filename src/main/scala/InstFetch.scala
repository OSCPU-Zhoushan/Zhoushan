package zhoushan

import chisel3._
import chisel3.util._

class InstFetch extends Module {
  val io = IO(new Bundle {
    val imem = new SimpleAxiIO
    val jmp_packet = Input(new JmpPacket)
    val stall = Input(Bool())
    val out = Output(new InstPacket)
  })

  val if_axi_id = 1.U(AxiParameters.AxiIdWidth.W)   // id = 1 for IF stage

  val s_init :: s_idle :: s_req :: s_wait :: Nil = Enum(4)
  val state = RegInit(s_init)

  val req = io.imem.req
  val resp = io.imem.resp

  val stall = io.stall

  val pc_init = "h80000000".U(32.W)
  val pc = RegInit(pc_init)
  val inst = RegInit(0.U(32.W))
  val bp = Module(new BrPredictor)
  val bp_pred_pc = bp.io.pred_pc

  req.bits.id := if_axi_id
  req.bits.addr := pc.asUInt()
  req.bits.ren := true.B          // read-only imem
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen := false.B
  req.valid := (state === s_req) && !stall
  
  resp.ready := true.B

  /* FSM to handle SimpleAxi bus status
   *
   *  Simplified FSM digram (no stall signal here)
   *
   *             mis_predict    mis_predict  !resp_success
   *                     ┌─┐  ┌───────────┐ ┌─┐
   *                     | v  v           | | v
   *   ┌────────┐      ┌────────┐      ┌────────┐
   *   │ s_init | ───> | s_req  | ───> | s_wait |
   *   └────────┘      └────────┘      └────────┘
   *                       ^               |
   *                       |               | resp_success & (mis_count == 0)
   *                   ┌────────┐          |
   *                   | s_idle | <────────┘
   *                   └────────┘
   *
   *  Note 1: When a mis-predict occurs, mis_count += 1
   *  Note 2: stall == 1 -> stop FSM, but don't send request more than once
   *
   */

  val resp_success = resp.fire() && resp.bits.id === if_axi_id && resp.bits.rlast
  val mis_count = RegInit(0.U(4.W))

  switch (state) {
    is (s_init) {
      state := s_req
    }
    is (s_idle) {
      pc := bp_pred_pc
      state := Mux(stall, s_idle, s_req)
    }
    is (s_req) {
      when (io.jmp_packet.mis) {
        pc := bp_pred_pc
        mis_count := mis_count + Mux(stall, 0.U, 1.U)
        state := s_req
      } .otherwise {
        state := Mux(stall, s_req, s_wait)
      }
    }
    is (s_wait) {
      when (io.jmp_packet.mis) {
        pc := bp_pred_pc
        mis_count := mis_count + 1.U
        state := s_req
      } .elsewhen (resp_success) {
        when (mis_count === 0.U) {
          inst := Mux(pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
          state := Mux(io.stall, s_wait, s_idle)
        } .otherwise {
          mis_count := mis_count - 1.U
        }
      }
    }
  }

  /* Branch predictor logic */

  bp.io.pc := pc
  bp.io.inst := inst
  bp.io.is_br := (inst === Instructions.JAL) || (inst === Instructions.JALR) ||
                 (inst === Instructions.BEQ) || (inst === Instructions.BNE) ||
                 (inst === Instructions.BLT) || (inst === Instructions.BLTU) ||
                 (inst === Instructions.BGE) || (inst === Instructions.BGEU);
  bp.io.jmp_packet <> io.jmp_packet

  io.out.pc := Mux(state === s_idle && !stall, pc, 0.U)
  io.out.inst := Mux(state === s_idle && !stall, inst, 0.U)
  io.out.pred_br := bp.io.pred_br
  io.out.pred_pc := bp.io.pred_pc
}
