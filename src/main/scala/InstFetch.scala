package zhoushan

import chisel3._

class InstFetch extends Module {
  val io = IO(new Bundle {
    val imem = Flipped(new RomIO)
    val pc = Output(UInt(32.W))
    val inst = Output(UInt(32.W))
    val jmp = Input(Bool())
    val jmp_pc = Input(UInt(32.W))
    val stall = Input(Bool())
  })

  val pc_init = "h80000000".U(32.W)
  val pc = RegInit(pc_init)
  val inst = io.imem.rdata(31, 0)
  io.imem.en := true.B
  io.imem.addr := pc.asUInt()

  val bp = Module(new BrPredictor)
  bp.io.pc := pc
  bp.io.inst := inst
  bp.io.is_jal := (inst === Instructions.JAL)

  val pc_zero_reset = RegInit(true.B) // todo: fix pc reset
  pc_zero_reset := false.B
  pc := Mux(pc_zero_reset, pc_init, 
        Mux(io.jmp, io.jmp_pc, 
        Mux(io.stall, pc, bp.io.pred_pc)))

  io.pc := pc
  io.inst := inst
}
