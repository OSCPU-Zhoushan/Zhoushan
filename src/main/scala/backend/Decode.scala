package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import zhoushan.Constant._

class Decode extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new InstPacketVec(DecodeWidth)))
    val out = Decoupled(new MicroOpVec(DecodeWidth))
    val flush = Input(Bool())
  })

  // store InstPacket when out is not ready
  val reg_in = RegInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new InstPacket))))
  val reg_in_valid = RegInit(false.B)

  when (io.flush || io.out.fire()) {
    reg_in_valid := false.B
  } .elsewhen (io.in.valid && !io.flush && !io.out.ready) {
    reg_in := io.in.bits.vec
    reg_in_valid := true.B
  }

  val decoder = for (i <- 0 until DecodeWidth) yield {
    val decoder = Module(new Decoder)
    decoder
  }

  for (i <- 0 until DecodeWidth) {
    decoder(i).io.in := Mux(io.in.valid, io.in.bits.vec(i), reg_in(i))
    decoder(i).io.in_valid := io.in.valid || reg_in_valid
  }

  // handshake signals
  io.in.ready := io.out.ready

  when (io.out.ready && RegNext(!io.out.ready) && !io.in.valid) {
    io.out.valid := reg_in_valid
    reg_in_valid := false.B
  } .otherwise {
    io.out.valid := io.in.valid
  }

  for (i <- 0 until DecodeWidth) {
    io.out.bits.vec(i) := Mux(io.out.fire(), decoder(i).io.out, 0.U.asTypeOf(new MicroOp))
  }

}

class Decoder extends Module {
  val io = IO(new Bundle {
    val in = Input(new InstPacket)
    val in_valid = Input(Bool())
    val out = Output(new MicroOp)
  })

  val inst = io.in.inst
  val uop = WireInit(0.U.asTypeOf(new MicroOp))

  uop.pc := io.in.pc
  uop.npc := io.in.pc + 4.U
  uop.inst := inst

  uop.rs1_addr := inst(19, 15)
  uop.rs2_addr := inst(24, 20)
  uop.rd_addr := inst(11, 7)

  uop.pred_br := io.in.pred_br
  uop.pred_bpc := io.in.pred_bpc

  val decode_result = decoder(minimizer = EspressoMinimizer,
                              input = inst,
                              truthTable = DecodeConfig.decode_table)

  uop.from_decoder(decode_result, inst(11, 7))

  io.out := Mux(io.in_valid, uop, 0.U.asTypeOf(new MicroOp))
}
