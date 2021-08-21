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

  val s_reset :: s_init :: s_idle :: s_wait :: s_stall :: Nil = Enum(5)
  val state = RegInit(s_reset)

  val req = io.imem.req
  val resp = io.imem.resp

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
  req.valid := (state === s_idle) || (state === s_init)
  
  resp.ready := true.B

  /* FSM to handle SimpleAxi bus status */

  when (state === s_reset) {
    state := s_init
  } .elsewhen (state === s_init || state === s_idle) {
    pc := bp_pred_pc
    state := s_wait
  } .elsewhen (state === s_wait) {
    when (resp.fire() && resp.bits.id === if_axi_id && resp.bits.rlast) {
      inst := Mux(pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
      state := Mux(io.stall, s_stall, s_idle)
    }
  } .otherwise {  // s_stall
    state := Mux(io.stall, s_stall, s_idle)
  }

  /* Branch predictor logic */

  bp.io.pc := pc
  bp.io.inst := inst
  bp.io.is_br := (inst === Instructions.JAL) || (inst === Instructions.JALR) ||
                 (inst === Instructions.BEQ) || (inst === Instructions.BNE) ||
                 (inst === Instructions.BLT) || (inst === Instructions.BLTU) ||
                 (inst === Instructions.BGE) || (inst === Instructions.BGEU);
  bp.io.jmp_packet <> io.jmp_packet

  io.out.pc := Mux(state === s_idle, pc, 0.U)
  io.out.inst := Mux(state === s_idle, inst, 0.U)
  io.out.pred_br := bp.io.pred_br
  io.out.pred_pc := bp.io.pred_pc
}
