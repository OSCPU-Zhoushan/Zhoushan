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
    val out_valid = Output(Bool())
    val next_pc = Output(UInt(32.W))
  })

  val uop = io.uop

  val in1 = Wire(UInt(64.W))
  val in2 = Wire(UInt(64.W))

  in1 := MuxLookup(uop.rs1_src, 0.U, Array(
    RS_FROM_RF  -> io.rs1_data,
    RS_FROM_IMM -> Cat(Fill(32, uop.imm(31)), uop.imm),
    RS_FROM_PC  -> Cat(Fill(32, 0.U), uop.pc),
    RS_FROM_NPC -> Cat(Fill(32, 0.U), uop.npc)
  )).asUInt()

  in2 := MuxLookup(uop.rs2_src, 0.U, Array(
    RS_FROM_RF  -> io.rs2_data,
    RS_FROM_IMM -> Cat(Fill(32, uop.imm(31)), uop.imm),
    RS_FROM_PC  -> Cat(Fill(32, 0.U), uop.pc),
    RS_FROM_NPC -> Cat(Fill(32, 0.U), uop.npc)
  )).asUInt()

  val shamt = Wire(UInt(6.W))
  shamt := Mux(uop.w_type, in2(4, 0).asUInt(), in2(5, 0)) 

  val alu_out_0, alu_out = Wire(UInt(64.W))
  val jmp_out = Wire(Bool())
  val jmp_addr = Wire(UInt(32.W))
  val npc_to_rd = Wire(UInt(64.W))

  alu_out_0 := MuxLookup(uop.alu_code, 0.U, Array(
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

  alu_out := Mux(uop.w_type, Cat(Fill(32, alu_out_0(31)), alu_out_0(31, 0)), alu_out_0)

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
    JMP_JAL  -> Cat(Fill(32, 0.U), uop.npc),
    JMP_JALR -> Cat(Fill(32, 0.U), uop.npc)
  ))

  val ls_addr = in1 + uop.imm
  val ls_addr_offset = ls_addr(2, 0)
  val ls_addr_nextline = ls_addr + "b1000".U
  val ls_addr_offset_nextline = (~ls_addr_offset) + 1.U;

  val ls_mask = MuxLookup(ls_addr_offset, 0.U, Array(
    "b000".U -> "hffffffffffffffff".U,
    "b001".U -> "hffffffffffffff00".U,
    "b010".U -> "hffffffffffff0000".U,
    "b011".U -> "hffffffffff000000".U,
    "b100".U -> "hffffffff00000000".U,
    "b101".U -> "hffffff0000000000".U,
    "b110".U -> "hffff000000000000".U,
    "b111".U -> "hff00000000000000".U
  ))
  val ls_mask_nextline = ~ls_mask;
  val wmask = MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> "h00000000000000ff".U,
    MEM_HALF  -> "h000000000000ffff".U,
    MEM_WORD  -> "h00000000ffffffff".U,
    MEM_DWORD -> "hffffffffffffffff".U
  ))

  val load_data = Wire(UInt(64.W))
  val load_data_reg = RegNext(load_data)

  val dmem = Module(new RAMHelper)
  dmem.io.clk := clock
  dmem.io.en := (uop.fu_code === FU_MEM)

  // may need to read/write memory in 2 lines
  val stall = RegInit(false.B)
  // half  -> offset = 111
  // word  -> offset = 101/110/111
  // dword -> offset != 000
  stall := Mux(uop.fu_code === FU_MEM, MuxLookup(uop.mem_size, false.B, Array(
    MEM_HALF  -> (ls_addr_offset === "b111".U),
    MEM_WORD  -> (ls_addr_offset.asUInt() > "b100".U),
    MEM_DWORD -> (ls_addr_offset =/= "b000".U)
  )), false.B)

  when (stall) {
    stall := false.B
  }

  // 0 = normal / read line 1, 1 = read line 2
  val dmem_state = RegInit(0.U(1.W))
  when (dmem_state === 0.U) {
    when (stall) { dmem_state := 1.U }
    dmem.io.rIdx := Cat(Fill(36, 0.U), ls_addr(30, 3))
    load_data := dmem.io.rdata >> (ls_addr_offset << 3)
    dmem.io.wIdx := Cat(Fill(36, 0.U), ls_addr(30, 3))
    dmem.io.wmask := ls_mask & ((wmask << (ls_addr_offset << 3))(63, 0))
    dmem.io.wdata := (in2 << (ls_addr_offset << 3))(63, 0)
  } .otherwise {
    dmem.io.rIdx := Cat(Fill(36, 0.U), ls_addr_nextline(30, 3))
    load_data := load_data_reg | (dmem.io.rdata << (ls_addr_offset_nextline << 3))
    dmem.io.wIdx := Cat(Fill(36, 0.U), ls_addr_nextline(30, 3))
    dmem.io.wmask := ls_mask_nextline & (wmask >> (ls_addr_offset_nextline << 3)).asUInt()
    dmem.io.wdata := (in2 >> (ls_addr_offset_nextline << 3)).asUInt()
  }

  dmem.io.wen := (uop.mem_code === MEM_ST)

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

  io.out := alu_out | npc_to_rd | load_out
  io.out_valid := !stall
  io.next_pc := Mux(jmp_out, jmp_addr, Mux(stall, uop.pc, uop.npc))

  // printf("pc=%x, mem_c=%x, size=%x, addr=%x, offset=%x, stall=%x, dmem.r/wIdx=%x, rdata=%x, wmask=%x, wdata=%x, wen=%x\n", 
  //         uop.pc, uop.mem_code, uop.mem_size, ls_addr, ls_addr_offset, stall, dmem.io.rIdx, dmem.io.rdata, dmem.io.wmask, dmem.io.wdata, dmem.io.wen)
}
