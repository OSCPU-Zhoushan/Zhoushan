package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._
import zhoushan.RasConstant._

class Execution extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    // input
    val in = Flipped(Decoupled(new MicroOpVec(IssueWidth)))
    val rs1_data = Vec(IssueWidth, Input(UInt(64.W)))
    val rs2_data = Vec(IssueWidth, Input(UInt(64.W)))
    // output
    val out = new MicroOpVec(IssueWidth)
    val rd_en = Vec(IssueWidth, Output(Bool()))
    val rd_addr = Vec(IssueWidth, Output(UInt(5.W)))
    val rd_data = Vec(IssueWidth, Output(UInt(64.W)))
    val jmp_packet = Output(new JmpPacket)
    val dmem = new CacheBusIO
    val intr = Output(Bool())
  })

  val uop = Mux(io.in.valid, io.in.bits.vec, VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new MicroOp))))
  val reg_uop = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new MicroOp))))
  val reg_valid = RegNext(!io.in.ready) && io.in.ready

  when (io.in.valid) {
    for (i <- 0 until IssueWidth) {
      reg_uop(i) := uop(i)
    }
  }

  val in1_0 = Wire(Vec(IssueWidth, UInt(64.W)))
  val in2_0 = Wire(Vec(IssueWidth, UInt(64.W)))
  val in1 = Wire(Vec(IssueWidth, UInt(64.W)))
  val in2 = Wire(Vec(IssueWidth, UInt(64.W)))

  for (i <- 0 until IssueWidth) {
    in1_0(i) := MuxLookup(uop(i).rs1_src, 0.U, Array(
      RS_FROM_RF  -> io.rs1_data(i),
      RS_FROM_IMM -> SignExt32_64(uop(i).imm),
      RS_FROM_PC  -> ZeroExt32_64(uop(i).pc),
      RS_FROM_NPC -> ZeroExt32_64(uop(i).npc)
    ))(63, 0)

    in2_0(i) := MuxLookup(uop(i).rs2_src, 0.U, Array(
      RS_FROM_RF  -> io.rs2_data(i),
      RS_FROM_IMM -> SignExt32_64(uop(i).imm),
      RS_FROM_PC  -> ZeroExt32_64(uop(i).pc),
      RS_FROM_NPC -> ZeroExt32_64(uop(i).npc)
    ))(63, 0)

    in1(i) := Mux(uop(i).w_type, Mux(uop(i).alu_code === ALU_SRL, ZeroExt32_64(in1_0(i)(31, 0)), SignExt32_64(in1_0(i)(31, 0))), in1_0(i))
    in2(i) := Mux(uop(i).w_type, SignExt32_64(in2_0(i)(31, 0)), in2_0(i))
  }

  val pipe0 = Module(new ExPipe0)
  pipe0.io.uop := uop(0)
  pipe0.io.in1 := in1(0)
  pipe0.io.in2 := in2(0)
  io.jmp_packet := pipe0.io.jmp_packet
  io.dmem <> pipe0.io.dmem
  io.intr := pipe0.io.intr

  val pipe1 = Module(new ExPipe1)
  pipe1.io.uop := uop(1)
  pipe1.io.in1 := in1(1)
  pipe1.io.in2 := in2(1)

  val reg_pipe1_result = RegInit(0.U(64.W))
  when (pipe0.io.busy && io.in.valid) {
    reg_pipe1_result := pipe1.io.result
  }

  io.in.ready := !pipe0.io.busy

  // pipeline registers

  val out_uop = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new MicroOp))))
  val out_rd_en = WireInit(VecInit(Seq.fill(IssueWidth)(false.B)))
  val out_rd_addr = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(5.W))))
  val out_rd_data = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))

  when (io.intr) {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
      out_rd_en(i) := 0.U
      out_rd_addr(i) := 0.U
      out_rd_data(i) := 0.U
    }
  } .elsewhen (pipe0.io.jmp_packet.mis) {
    for (i <- 0 until IssueWidth) {
      if (i == 0) {
        out_uop(i) := Mux(reg_valid, reg_uop(i), uop(i))
        out_rd_en(i) := Mux(reg_valid, reg_uop(i).rd_en, uop(i).rd_en)
        out_rd_addr(i) := Mux(reg_valid, reg_uop(i).rd_addr, uop(i).rd_addr)
        out_rd_data(i) := pipe0.io.result
      } else {
        out_uop(i) := 0.U.asTypeOf(new MicroOp)
        out_rd_en(i) := false.B
        out_rd_addr(i) := 0.U
        out_rd_data(i) := 0.U
      }
    }
  } .elsewhen (!pipe0.io.busy) {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := Mux(reg_valid, reg_uop(i), uop(i))
      out_rd_en(i) := Mux(reg_valid, reg_uop(i).rd_en, uop(i).rd_en)
      out_rd_addr(i) := Mux(reg_valid, reg_uop(i).rd_addr, uop(i).rd_addr)
    }
    out_rd_data(0) := pipe0.io.result
    out_rd_data(1) := Mux(reg_valid, reg_pipe1_result, pipe1.io.result)
  } .otherwise {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
      out_rd_en(i) := 0.U
      out_rd_addr(i) := 0.U
      out_rd_data(i) := 0.U
    }
  }

  io.out.vec := out_uop
  io.rd_en := out_rd_en
  io.rd_addr := out_rd_addr
  io.rd_data := out_rd_data

}

class ExPipe0 extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val result = Output(UInt(64.W))
    val busy = Output(Bool())
    val jmp_packet = Output(new JmpPacket)
    val dmem = new CacheBusIO
    val intr = Output(Bool())
  })

  val intr = WireInit(false.B)

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val alu = Module(new Alu)
  alu.io.uop := uop
  alu.io.in1 := in1
  alu.io.in2 := in2

  val lsu = Module(new Lsu)
  lsu.io.uop := uop
  lsu.io.in1 := in1
  lsu.io.in2 := in2
  lsu.io.dmem <> io.dmem
  lsu.io.intr := intr

  val csr = Module(new Csr)
  csr.io.uop := uop
  csr.io.in1 := in1
  intr := csr.io.intr

  val busy = lsu.io.busy

  val jmp = MuxLookup(uop.fu_code, false.B, Array(
    FU_JMP -> alu.io.jmp,
    FU_CSR -> csr.io.jmp
  )) || intr

  val jmp_pc = Mux(intr, csr.io.intr_pc, 
    MuxLookup(uop.fu_code, 0.U, Array(
      FU_JMP -> alu.io.jmp_pc,
      FU_CSR -> csr.io.jmp_pc
    )
  ))

  io.result := alu.io.out | lsu.io.out | csr.io.out
  io.busy := busy
  io.intr := intr

  val mis_predict = Mux(jmp, (uop.pred_br && (jmp_pc =/= uop.pred_bpc)) || !uop.pred_br, uop.pred_br)

  io.jmp_packet.valid := (uop.fu_code === FU_JMP) || csr.io.jmp || csr.io.intr
  io.jmp_packet.inst_pc := uop.pc
  io.jmp_packet.jmp := jmp
  io.jmp_packet.jmp_pc := jmp_pc
  io.jmp_packet.mis := io.jmp_packet.valid && mis_predict
  io.jmp_packet.intr := intr

  // ref: riscv-spec-20191213 page 21-22
  val rd_link = (uop.rd_addr === 1.U || uop.rd_addr === 5.U)
  val rs1_link = (uop.rs1_addr === 1.U || uop.rs1_addr === 5.U)
  val ras_type = WireInit(RAS_X)
  when (uop.jmp_code === JMP_JAL) {
    when (rd_link) {
      ras_type := RAS_PUSH
    }
  }
  when (uop.jmp_code === JMP_JALR) {
    ras_type := MuxLookup(Cat(rd_link.asUInt(), rs1_link.asUInt()), RAS_X, Array(
      "b00".U -> RAS_X,
      "b01".U -> RAS_POP,
      "b10".U -> RAS_PUSH,
      "b11".U -> Mux(uop.rd_addr === uop.rs1_addr, RAS_PUSH, RAS_POP_THEN_PUSH)
    ))
  }
  io.jmp_packet.ras_type := ras_type
}

class ExPipe1 extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val result = Output(UInt(64.W))
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val alu = Module(new Alu)
  alu.io.uop := uop
  alu.io.in1 := in1
  alu.io.in2 := in2

  io.result := alu.io.out
}
