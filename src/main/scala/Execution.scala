package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class Execution extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1_data = Input(UInt(64.W))
    val in2_data = Input(UInt(64.W))
    val out_data = Output(UInt(64.W))
  })

  val uop = io.uop

  io.out_data := 0.U

  when (uop.fu_code === FU_ALU) {
    switch (uop.alu_code) {
      is (ALU_ADD) { io.out_data := io.in1_data + io.in2_data }
    }
  }

}
