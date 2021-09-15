package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class Execution extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    // input
    val in_uop = Vec(IssueWidth, Input(new MicroOp))
    val rs1_data = Vec(IssueWidth, Input(UInt(64.W)))
    val rs2_data = Vec(IssueWidth, Input(UInt(64.W)))
    // output
    val out_uop = Vec(IssueWidth, Output(new MicroOp))
    val out_jcp = Vec(IssueWidth - 1, Output(new JmpCommitPacket))
    val rd_en = Vec(IssueWidth, Output(Bool()))
    val rd_paddr = Vec(IssueWidth, Output(UInt(6.W)))
    val rd_data = Vec(IssueWidth, Output(UInt(64.W)))
    // from subsequent stage
    val flush = Input(Bool())
    // to previous stage
    val lsu_ready = Output(Bool())
    // dmem
    val dmem = new CacheBusIO
  })

  val uop = io.in_uop

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

  val pipe1 = Module(new ExPipe1)
  pipe1.io.uop := uop(1)
  pipe1.io.in1 := in1(1)
  pipe1.io.in2 := in2(1)

  val pipe2 = Module(new ExPipe2)
  pipe2.io.uop := uop(2)
  pipe2.io.in1 := in1(2)
  pipe2.io.in2 := in2(2)
  io.lsu_ready := pipe2.io.ready
  io.dmem <> pipe2.io.dmem

  // pipeline registers

  val out_uop = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new MicroOp))))
  val out_jcp = RegInit(VecInit(Seq.fill(IssueWidth - 1)(0.U.asTypeOf(new JmpCommitPacket)))) 
  val out_rd_en = WireInit(VecInit(Seq.fill(IssueWidth)(false.B)))
  val out_rd_paddr = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(6.W))))
  val out_rd_data = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))

  when (io.flush) {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
      if (i < IssueWidth - 1) {
        out_jcp(i) := 0.U.asTypeOf(new JmpCommitPacket)
      }
      out_rd_en(i) := false.B
      out_rd_paddr(i) := 0.U
      out_rd_data(i) := 0.U
    }
  } .otherwise {
    // pipe 0
    out_uop     (0) := uop(0)
    out_jcp     (0) := pipe0.io.jcp
    out_rd_en   (0) := uop(0).rd_en
    out_rd_paddr(0) := uop(0).rd_paddr
    out_rd_data (0) := pipe0.io.out

    // pipe 1
    out_uop     (1) := uop(1)
    out_jcp     (1) := pipe1.io.jcp
    out_rd_en   (1) := uop(1).rd_en
    out_rd_paddr(1) := uop(1).rd_paddr
    out_rd_data (1) := pipe1.io.out

    // pipe 2
    out_uop     (2) := Mux(pipe2.io.ready, uop(2), 0.U.asTypeOf(new MicroOp))
    out_rd_en   (2) := Mux(pipe2.io.ready, uop(2).rd_en, false.B)
    out_rd_paddr(2) := uop(2).rd_paddr
    out_rd_data (2) := pipe2.io.out
  }

  io.out_uop  := out_uop
  io.out_jcp  := out_jcp
  io.rd_en    := out_rd_en
  io.rd_paddr := out_rd_paddr
  io.rd_data  := out_rd_data

}

// Execution Pipe 0
//   1 ALU + 1 CSR
//   todo: add CSR
class ExPipe0 extends Module {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    // output
    val out = Output(UInt(64.W))
    val jcp = Output(new JmpCommitPacket)
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val alu = Module(new Alu)
  alu.io.uop := uop
  alu.io.in1 := in1
  alu.io.in2 := in2

  io.out := alu.io.out
  io.jcp := alu.io.jcp
}

// Execution Pipe 1
//   1 ALU
class ExPipe1 extends Module {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    // output
    val out = Output(UInt(64.W))
    val jcp = Output(new JmpCommitPacket)
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val alu = Module(new Alu)
  alu.io.uop := uop
  alu.io.in1 := in1
  alu.io.in2 := in2

  io.out := alu.io.out
  io.jcp := alu.io.jcp
}

// Execution Pipe 2
//   1 LSU
class ExPipe2 extends Module {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    // output
    val out = Output(UInt(64.W))
    val ready = Output(Bool())
    // dmem
    val dmem = new CacheBusIO
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val lsu = Module(new Lsu)
  lsu.io.uop := uop
  lsu.io.in1 := in1
  lsu.io.in2 := in2
  lsu.io.dmem <> io.dmem

  io.out := lsu.io.out
  io.ready := !lsu.io.busy
}
