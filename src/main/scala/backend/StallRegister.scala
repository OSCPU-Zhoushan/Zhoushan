package zhoushan

import chisel3._
import chisel3.util._

class StallRegister extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in = Flipped(Decoupled(new MicroOpVec(DecodeWidth)))
    val out = Decoupled(new MicroOpVec(DecodeWidth))
  })

  val in = io.in.bits.vec

  // store input when out is not ready
  val reg_in = RegInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))
  val reg_in_valid = RegInit(false.B)

  when (io.flush || io.out.fire()) {
    reg_in_valid := false.B
  } .elsewhen (io.in.valid && !io.flush && !io.out.ready) {
    reg_in := in
    reg_in_valid := true.B
  }

  when (io.out.ready && RegNext(!io.out.ready)) {
    io.out.bits.vec := reg_in
    io.out.valid := reg_in_valid
  } .otherwise {
    io.out.bits.vec := in
    io.out.valid := io.in.valid
  }
  io.in.ready := io.out.ready
}
