package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class ExCommitPacket extends Bundle {
  val store_valid = Bool()
  val mmio = Bool()
  val jmp_valid = Bool()
  val jmp = Bool()
  val jmp_pc = UInt(32.W)
  val mis = Bool()
  val rd_data = UInt(64.W)
}

class Execution extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    // input
    val in = Vec(IssueWidth, Input(new MicroOp))
    val rs1_data = Vec(IssueWidth, Input(UInt(64.W)))
    val rs2_data = Vec(IssueWidth, Input(UInt(64.W)))
    // output
    val out = Vec(IssueWidth, Output(new MicroOp))
    val out_ecp = Vec(IssueWidth, Output(new ExCommitPacket))
    val rd_en = Vec(IssueWidth, Output(Bool()))
    val rd_paddr = Vec(IssueWidth, Output(UInt(log2Up(PrfSize).W)))
    val rd_data = Vec(IssueWidth, Output(UInt(64.W)))
    // from subsequent stage
    val flush = Input(Bool())
    // to previous stage
    val lsu_ready = Output(Bool())
    // dmem
    val dmem_st = new CacheBusIO
    val dmem_ld = new CacheBusIO
  })

  val uop = io.in

  val reg_uop_lsu = RegInit(0.U.asTypeOf(new MicroOp))
  val reg_valid = RegNext(!io.lsu_ready) && io.lsu_ready

  when (uop(IssueWidth - 1).valid) {
    reg_uop_lsu := uop(IssueWidth - 1)
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

  val pipe1 = Module(new ExPipe1)
  pipe1.io.uop := uop(1)
  pipe1.io.in1 := in1(1)
  pipe1.io.in2 := in2(1)

  val pipe2 = Module(new ExPipe2)
  pipe2.io.uop := uop(2)
  pipe2.io.in1 := in1(2)
  pipe2.io.in2 := in2(2)
  io.lsu_ready := pipe2.io.ready
  pipe2.io.dmem_st <> io.dmem_st
  pipe2.io.dmem_ld <> io.dmem_ld
  pipe2.io.flush := io.flush

  // pipeline registers

  val out_uop = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new MicroOp))))
  val out_ecp = RegInit(VecInit(Seq.fill(IssueWidth)(0.U.asTypeOf(new ExCommitPacket))))
  val out_rd_en = WireInit(VecInit(Seq.fill(IssueWidth)(false.B)))
  val out_rd_paddr = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(log2Up(PrfSize).W))))
  val out_rd_data = WireInit(VecInit(Seq.fill(IssueWidth)(0.U(64.W))))

  when (io.flush) {
    for (i <- 0 until IssueWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
      out_ecp(i) := 0.U.asTypeOf(new ExCommitPacket)
      out_rd_en(i) := false.B
      out_rd_paddr(i) := 0.U
      out_rd_data(i) := 0.U
    }
    reg_uop_lsu := 0.U.asTypeOf(new MicroOp)
  } .otherwise {
    // pipe 0
    out_uop     (0) := uop(0)
    out_ecp     (0) := pipe0.io.ecp
    out_rd_en   (0) := uop(0).rd_en
    out_rd_paddr(0) := uop(0).rd_paddr
    out_rd_data (0) := pipe0.io.ecp.rd_data

    // pipe 1
    out_uop     (1) := uop(1)
    out_ecp     (1) := pipe1.io.ecp
    out_rd_en   (1) := uop(1).rd_en
    out_rd_paddr(1) := uop(1).rd_paddr
    out_rd_data (1) := pipe1.io.ecp.rd_data

    // pipe 2
    out_uop     (2) := Mux(reg_valid, reg_uop_lsu, 0.U.asTypeOf(new MicroOp))
    out_ecp     (2) := pipe2.io.ecp
    out_rd_en   (2) := Mux(reg_valid, reg_uop_lsu.rd_en, false.B)
    out_rd_paddr(2) := reg_uop_lsu.rd_paddr
    out_rd_data (2) := pipe2.io.ecp.rd_data
  }

  io.out      := out_uop
  io.out_ecp  := out_ecp
  io.rd_en    := out_rd_en
  io.rd_paddr := out_rd_paddr
  io.rd_data  := out_rd_data

}

// Execution Pipe 0
//   1 ALU + 1 CSR + 1 FENCEI
class ExPipe0 extends Module {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    // output
    val ecp = Output(new ExCommitPacket)
  })

  val alu = Module(new Alu)
  alu.io.uop := io.uop
  alu.io.in1 := io.in1
  alu.io.in2 := io.in2

  val csr = Module(new Csr)
  csr.io.uop := io.uop
  csr.io.in1 := io.in1

  io.ecp := 0.U.asTypeOf(new ExCommitPacket)
  when (io.uop.fu_code === FU_ALU || io.uop.fu_code === FU_JMP) {
    io.ecp := alu.io.ecp
  } .elsewhen (io.uop.fu_code === FU_SYS && io.uop.sys_code =/= SYS_FENCEI) {
    io.ecp := csr.io.ecp
  }
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
    val ecp = Output(new ExCommitPacket)
  })

  val alu = Module(new Alu)
  alu.io.uop := io.uop
  alu.io.in1 := io.in1
  alu.io.in2 := io.in2

  io.ecp := alu.io.ecp
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
    val ecp = Output(new ExCommitPacket)
    val ready = Output(Bool())
    // dmem
    val dmem_st = new CacheBusIO
    val dmem_ld = new CacheBusIO
    // flush signal
    val flush = Input(Bool())
  })

  val lsu = Module(new Lsu)
  lsu.io.uop := io.uop
  lsu.io.in1 := io.in1
  lsu.io.in2 := io.in2
  lsu.io.dmem_st <> io.dmem_st
  lsu.io.dmem_ld <> io.dmem_ld
  lsu.io.flush := io.flush

  io.ecp := lsu.io.ecp
  io.ready := !lsu.io.busy
}
