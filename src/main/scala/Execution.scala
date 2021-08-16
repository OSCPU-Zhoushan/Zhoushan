package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._

class Execution extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val rs1_data = Input(UInt(64.W))
    val rs2_data = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val jmp = Output(Bool())
    val next_pc = Output(UInt(32.W))
  })

  val uop = io.uop

  val in1 = Wire(UInt(64.W))
  val in2 = Wire(UInt(64.W))

  in1 := MuxLookup(uop.rs1_src, 0.U, Array(
    RS_FROM_RF  -> io.rs1_data,
    RS_FROM_IMM -> Cat(Fill(32, uop.imm(31)), uop.imm),
    RS_FROM_PC  -> Cat(Fill(32, uop.pc(31)), uop.pc),
    RS_FROM_NPC -> Cat(Fill(32, uop.npc(31)), uop.npc)
  )).asUInt()

  in2 := MuxLookup(uop.rs2_src, 0.U, Array(
    RS_FROM_RF  -> io.rs2_data,
    RS_FROM_IMM -> Cat(Fill(32, uop.imm(31)), uop.imm),
    RS_FROM_PC  -> Cat(Fill(32, uop.pc(31)), uop.pc),
    RS_FROM_NPC -> Cat(Fill(32, uop.npc(31)), uop.npc)
  )).asUInt()

  val shamt = in2(4, 0).asUInt()

  val alu_out = Wire(UInt(64.W))
  val jmp_out = Wire(Bool())
  val jmp_addr = Wire(UInt(32.W))
  val npc_to_rd = Wire(UInt(64.W))

  alu_out := MuxLookup(uop.alu_code, 0.U, Array(
    ALU_ADD  -> (in1 + in2).asUInt(),
    ALU_SUB  -> (in1 - in2).asUInt(),
    ALU_SLT  -> (in1.asSInt() < in2.asSInt()).asUInt(),
    ALU_SLTU -> (in1 < in2).asUInt(),
    ALU_XOR  -> (in1 ^ in2).asUInt(),
    ALU_OR   -> (in1 | in2).asUInt(),
    ALU_AND  -> (in1 & in2).asUInt(),
    ALU_SLL  -> ((in1 << shamt)(63, 0)).asUInt(),
    ALU_SRL  -> (in1.asSInt() >> shamt).asUInt(),
    ALU_SRA  -> (in1.asUInt() >> shamt).asUInt()
  ))

  jmp_out := MuxLookup(uop.jmp_code, false.B, Array(
    JMP_JAL  -> true.B,
    JMP_JALR -> true.B,
    JMP_BEQ  -> (in1 === in2),
    JMP_BNE  -> (in1 =/= in2),
    JMP_BLT  -> (in1.asSInt() < in2.asSInt()),
    JMP_BGE  -> (in1.asSInt() >= in2.asSInt()),
    JMP_BLTU -> (in1.asUInt() < in2.asUInt()),
    JMP_BGEU -> (in1.asUInt() >= in2.asUInt())
  ))

  jmp_addr := MuxLookup(uop.jmp_code, uop.npc, Array(
    JMP_JAL  -> (uop.pc + uop.imm),
    JMP_JALR -> (in1(31, 0) + uop.imm),
    JMP_BEQ  -> Mux(jmp_out, uop.pc + uop.imm, uop.npc),
    JMP_BNE  -> Mux(jmp_out, uop.pc + uop.imm, uop.npc),
    JMP_BLT  -> Mux(jmp_out, uop.pc + uop.imm, uop.npc),
    JMP_BGE  -> Mux(jmp_out, uop.pc + uop.imm, uop.npc),
    JMP_BLTU -> Mux(jmp_out, uop.pc + uop.imm, uop.npc),
    JMP_BGEU -> Mux(jmp_out, uop.pc + uop.imm, uop.npc)
  ))

  npc_to_rd := MuxLookup(uop.jmp_code, 0.U, Array(
    JMP_JAL  -> Cat(Fill(32, uop.npc(31)), uop.npc),
    JMP_JALR -> Cat(Fill(32, uop.npc(31)), uop.npc)
  ))

  val ls_addr = in1 + uop.imm
  val load_data = Wire(UInt(64.W))

  val dmem = Module(new RAMHelper)
  dmem.io.clk := clock
  dmem.io.en := (uop.fu_code === FU_MEM)
  dmem.io.rIdx := Cat(Fill(35, 0.U), ls_addr(31, 3))
  load_data := Mux(ls_addr(2), dmem.io.rdata(63, 32), dmem.io.rdata(31, 0))

  val ld_out = Wire(UInt(64.W))
  val ldu_out = Wire(UInt(64.W))
  val load_out = Wire(UInt(64.W))

  ld_out := Mux(uop.mem_code === MEM_LD, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, load_data(7)), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, load_data(15)), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, load_data(31)), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  ldu_out := Mux(uop.mem_code === MEM_LDU, MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, 0.U), load_data(7, 0)),
    MEM_HALF  -> Cat(Fill(48, 0.U), load_data(15, 0)),
    MEM_WORD  -> Cat(Fill(32, 0.U), load_data(31, 0)),
    MEM_DWORD -> load_data
  )), 0.U)

  load_out := MuxLookup(uop.mem_code, 0.U, Array(
    MEM_LD  -> ld_out,
    MEM_LDU -> ldu_out
  ))

  dmem.io.wIdx := Cat(Fill(35, 0.U), ls_addr(31, 3))
  dmem.io.wdata := in2
  dmem.io.wmask := MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> Cat(Fill(56, 0.U), Fill(8, 1.U)),
    MEM_HALF  -> Cat(Fill(48, 0.U), Fill(16, 1.U)),
    MEM_WORD  -> Cat(Fill(32, 0.U), Fill(32, 1.U)),
    MEM_DWORD -> Fill(64, 1.U)
  ))
  dmem.io.wen := (uop.mem_code === MEM_ST)

  io.out := alu_out | npc_to_rd | load_out
  io.jmp := jmp_out
  io.next_pc := Mux(jmp_out, jmp_addr, uop.npc)

  // printf("mem_code = %x, mem_size = %x, dmem.r/wIdx = %x, dmem.rdata = %x, dmem.wmask = %x, dmem.wen = %x\n", 
  //         uop.mem_code, uop.mem_size, dmem.io.rIdx, dmem.io.rdata, dmem.io.wmask, dmem.io.wen)
}
