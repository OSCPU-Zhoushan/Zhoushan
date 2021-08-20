package zhoushan

import chisel3._
import chisel3.util._

class BrPredictorIO extends Bundle {
  // from IF stage
  val pc = Input(UInt(32.W))
  val inst = Input(UInt(32.W))
  val is_br = Input(Bool())
  // from EX stage
  val jmp_packet = Input(new JmpPacket)
  // prediction result
  val pred_br = Output(Bool())
  val pred_pc = Output(UInt(32.W))
}

class BrPredictor extends Module {
  val io = IO(new BrPredictorIO)

  val BhtWidth = 6
  val BhtSize = 256
  val BhtAddrSize = log2Up(BhtSize)     // 8
  val PhtWidth = 8
  val PhtIndexSize = log2Up(PhtWidth)   // 3 
  val PhtSize = 256
  val PhtAddrSize = log2Up(PhtSize)     // 8

  val pc = io.pc
  val inst = io.inst
  val is_br = io.is_br
  val packet = io.jmp_packet

  val pred_br = WireInit(false.B)
  val pred_pc = WireInit(0.U(32.W))

  val bht = RegInit(VecInit(Seq.fill(BhtSize)(0.U(BhtWidth.W))))
  val pht = RegInit(VecInit(Seq.fill(PhtWidth)(VecInit(Seq.fill(PhtSize)(1.U(2.W))))))

  val bht_raddr = pc(1 + BhtAddrSize, 2)
  val pht_raddr = bht(bht_raddr)
  val pht_rindex = pc(7 + PhtIndexSize, 8)
  val pred_result = pht(pht_rindex)(pht_raddr)

  pred_br := pred_result(1).asBool()

  when (packet.valid && packet.mis) {
    pred_pc := Mux(packet.jmp, packet.jmp_pc, pc + 4.U)
  } .otherwise {
    pred_pc := pc + 4.U
  }

  pred_pc := Mux(packet.jmp, packet.jmp_pc, pc + 4.U)

  io.pred_br := pred_br
  io.pred_pc := pred_pc

}
