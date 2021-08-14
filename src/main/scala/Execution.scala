package zhoushan

import chisel3._

class Execution extends Module {
  val io = IO(new Bundle {
    // val uop = Input(new MicroOp())
    val in1_data = Input(UInt(64.W))
    val in2_data = Input(UInt(64.W))
    val out_data = Output(UInt(64.W))
  })

  io.out_data := 0.U

  // when (io.uop.uopc === io.uop.UOPC_ADDI) {
  //   io.out_data := io.in1_data + io.in2_data
  // }

}
