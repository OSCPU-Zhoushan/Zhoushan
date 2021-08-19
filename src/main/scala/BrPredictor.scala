package zhoushan

import chisel3._
import chisel3.util._

class BrPredictor extends Module {
  val io = IO(new Bundle {
    // val jmp_update = Input(Bool())
    // val jmp = Input(Bool())
    // val jmp_pc = Input(UInt(32.W))
    val pc = Input(UInt(32.W))
    val inst = Input(UInt(32.W))
    val is_jal = Input(Bool())
    val pred_br = Output(Bool())
    val pred_pc = Output(UInt(32.W))
  })

  val inst = io.inst
  val is_jal = io.is_jal
  val imm_j = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U)

  io.pred_br := is_jal
  io.pred_pc := Mux(is_jal, io.pc + imm_j, io.pc + 4.U)
  io.pred_pc := io.pc + 4.U

  // 2-bit saturation counter

  // val strong_nt :: weak_nt :: weak_t :: strong_t :: Nil = Enum(4)
  // val state = RegInit(weak_nt)

  // when (io.jmp_update) {
  //   state := MuxLookup(state, weak_nt, Array(
  //     strong_nt -> Mux(io.jmp, weak_nt, strong_nt),
  //     weak_nt   -> Mux(io.jmp, weak_t, strong_nt),
  //     weak_t    -> Mux(io.jmp, strong_t, weak_nt),
  //     strong_t  -> Mux(io.jmp, strong_t, weak_t)
  //   ))
  // }

  // when (io.jmp_update) {
  //   switch (state) {
  //     is (strong_nt) { state := Mux(io.jmp, weak_nt, strong_nt) }
  //     is (weak_nt)   { state := Mux(io.jmp, weak_t, strong_nt)  }
  //     is (weak_t)    { state := Mux(io.jmp, strong_t, weak_nt)  }
  //     is (strong_t)  { state := Mux(io.jmp, strong_t, weak_t)  }
  //   }
  // }

  // io.pred_br := (state === weak_t) || (state === strong_t)

}
