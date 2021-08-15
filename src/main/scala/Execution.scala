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

  // temporary data memory
  val dmem = Mem(4096, UInt(8.W))

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

  val load_data = Cat(dmem(ls_addr + 7.U), dmem(ls_addr + 6.U), dmem(ls_addr + 5.U), dmem(ls_addr + 4.U),
                      dmem(ls_addr + 3.U), dmem(ls_addr + 2.U), dmem(ls_addr + 1.U), dmem(ls_addr))
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

  when (uop.mem_code === MEM_ST) {
    switch (uop.mem_size) {
      is (MEM_BYTE) { 
        dmem(ls_addr) := in2(7, 0)
      }
      is (MEM_HALF) { 
        dmem(ls_addr + 1.U) := in2(15, 8)
        dmem(ls_addr) := in2(7, 0)
      }
      is (MEM_WORD) {
        dmem(ls_addr + 3.U) := in2(31, 24)
        dmem(ls_addr + 2.U) := in2(23, 16)
        dmem(ls_addr + 1.U) := in2(15, 8)
        dmem(ls_addr) := in2(7, 0)
      }
      is (MEM_DWORD) {
        dmem(ls_addr + 7.U) := in2(63, 56)
        dmem(ls_addr + 6.U) := in2(55, 48)
        dmem(ls_addr + 5.U) := in2(47, 40)
        dmem(ls_addr + 4.U) := in2(39, 32)
        dmem(ls_addr + 3.U) := in2(31, 24)
        dmem(ls_addr + 2.U) := in2(23, 16)
        dmem(ls_addr + 1.U) := in2(15, 8)
        dmem(ls_addr) := in2(7, 0)
      }
    }
  }

  io.out := alu_out | npc_to_rd | load_out
  io.jmp := jmp_out
  io.next_pc := Mux(jmp_out, jmp_addr, uop.npc)

}
