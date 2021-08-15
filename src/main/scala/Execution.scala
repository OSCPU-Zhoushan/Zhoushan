package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class Execution extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val rs1_data = Input(UInt(64.W))
    val rs2_data = Input(UInt(64.W))
    val out_data = Output(UInt(64.W))
  })

  val uop = io.uop

  val in1_data = MuxLookup(uop.rs1_src, RS_X, Array(
    RS_FROM_RF -> io.rs1_data,
    RS_FROM_IMM -> uop.imm
  ))

  val in2_data = MuxLookup(uop.rs2_src, RS_X, Array(
    RS_FROM_RF -> io.rs2_data,
    RS_FROM_IMM -> uop.imm
  ))

  io.out_data := 0.U

  when (uop.fu_code === FU_ALU) {
    switch (uop.alu_code) {
      is (ALU_ADD) { io.out_data := in1_data + in2_data }
    }
  }

}
