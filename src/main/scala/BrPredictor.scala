package zhoushan

import chisel3._
import chisel3.util._

class BrPredictorIO extends Bundle {
  // from IF stage
  val pc = Input(UInt(32.W))
  val inst = Input(UInt(32.W))
  val is_br = Input(Bool())
  // from EX stage
  val jmp = Input(Bool())
  val jmp_pc = Input(UInt(32.W))
  // prediction result
  val pred_br = Output(Bool())
  val pred_pc = Output(UInt(32.W))
}

class BrPredictor extends Module {
  val io = IO(new BrPredictorIO)

  val BhtWidth = 6
  val BhtSize = 256
  val BhtAddrSize = log2Up(BhtSize)
  val PhtWidth = 8
  val PhtIndexSize = log2Up(PhtWidth)
  val PhtSize = 256
  val PhtAddrSize = log2Up(PhtSize)

  val pc = io.pc
  val inst = io.inst
  val is_br = io.is_br

  val pred_br = WireInit(false.B)
  val pred_pc = WireInit(0.U(32.W))

  val bht = RegInit(VecInit(Seq.fill(BhtSize)(0.U(BhtWidth.W))))
  val pht = RegInit(VecInit(Seq.fill(PhtWidth)(VecInit(Seq.fill(PhtSize)(0.U(2.W))))))

  pred_br := false.B
  pred_pc := pc + 4.U

  io.pred_br := pred_br
  io.pred_pc := pred_pc

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
