package zhoushan

import chisel3._

class InstFetch extends Module {
  val io = IO(new Bundle {
    val imem = Flipped(new RomIO)
    val jmp_packet = Input(new JmpPacket)
    val stall = Input(Bool())
    val out = Output(new InstPacket)
  })

  val pc_init = "h80000000".U(32.W)
  val pc = RegInit(pc_init)
  val inst = io.imem.rdata(31, 0)
  val jmp = io.jmp_packet.jmp
  val jmp_pc = io.jmp_packet.jmp_pc

  io.imem.en := true.B
  io.imem.addr := pc.asUInt()

  val bp = Module(new BrPredictor)
  bp.io.pc := pc
  bp.io.inst := inst
  bp.io.is_br := (inst === Instructions.JAL) || (inst === Instructions.JALR) ||
                 (inst === Instructions.BEQ) || (inst === Instructions.BNE) ||
                 (inst === Instructions.BLT) || (inst === Instructions.BLTU) ||
                 (inst === Instructions.BGE) || (inst === Instructions.BGEU);
  bp.io.jmp_packet <> io.jmp_packet

  val pc_zero_reset = RegInit(true.B) // todo: fix pc reset
  pc_zero_reset := false.B
  pc := Mux(pc_zero_reset, pc_init, 
        Mux(jmp, jmp_pc,
        Mux(io.stall, pc, bp.io.pred_pc)))

  io.out.pc := pc
  io.out.inst := inst
  io.out.pred_br := bp.io.pred_br
  io.out.pred_pc := bp.io.pred_pc
}
