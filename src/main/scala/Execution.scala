package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import zhoushan.Constant._

trait Ext {
  def SignExt32_64(x: UInt) : UInt = Cat(Fill(32, x(31)), x)
  def ZeroExt32_64(x: UInt) : UInt = Cat(Fill(32, 0.U), x)
}

class Alu extends Module with Ext {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val jmp = Output(Bool())
    val jmp_pc = Output(UInt(32.W))
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val shamt = Wire(UInt(6.W))
  shamt := Mux(uop.w_type, in2(4, 0).asUInt(), in2(5, 0))

  val alu_out_0, alu_out = Wire(UInt(64.W))
  val jmp = Wire(Bool())
  val jmp_pc = Wire(UInt(32.W))
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
    ALU_SRL  -> (in1.asUInt() >> shamt).asUInt(),
    ALU_SRA  -> (in1.asSInt() >> shamt).asUInt()
  ))

  alu_out := Mux(uop.w_type, SignExt32_64(alu_out_0(31, 0)), alu_out_0)

  jmp := MuxLookup(uop.jmp_code, false.B, Array(
    JMP_JAL  -> true.B,
    JMP_JALR -> true.B,
    JMP_BEQ  -> (in1 === in2),
    JMP_BNE  -> (in1 =/= in2),
    JMP_BLT  -> (in1.asSInt() < in2.asSInt()),
    JMP_BGE  -> (in1.asSInt() >= in2.asSInt()),
    JMP_BLTU -> (in1.asUInt() < in2.asUInt()),
    JMP_BGEU -> (in1.asUInt() >= in2.asUInt())
  ))

  jmp_pc := Mux(uop.jmp_code === JMP_JALR, in1(31, 0), uop.pc) + uop.imm

  npc_to_rd := MuxLookup(uop.jmp_code, 0.U, Array(
    JMP_JAL  -> ZeroExt32_64(uop.npc),
    JMP_JALR -> ZeroExt32_64(uop.npc)
  ))

  io.out := alu_out | npc_to_rd
  io.jmp := jmp
  io.jmp_pc := jmp_pc
}

class Lsu extends Module with Ext {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val in2 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val out_valid = Output(Bool())
    val dmem = Flipped(new RamIO)
  })

  val uop = io.uop
  val in1 = io.in1
  val in2 = io.in2

  val addr = in1 + SignExt32_64(uop.imm)
  val addr_offset = addr(2, 0)
  val addr_nextline = addr + "b1000".U
  val addr_offset_nextline = (~addr_offset) + 1.U;

  val mask = MuxLookup(addr_offset, 0.U, Array(
    "b000".U -> "hffffffffffffffff".U,
    "b001".U -> "hffffffffffffff00".U,
    "b010".U -> "hffffffffffff0000".U,
    "b011".U -> "hffffffffff000000".U,
    "b100".U -> "hffffffff00000000".U,
    "b101".U -> "hffffff0000000000".U,
    "b110".U -> "hffff000000000000".U,
    "b111".U -> "hff00000000000000".U
  ))
  val mask_nextline = ~mask;
  val wmask = MuxLookup(uop.mem_size, 0.U, Array(
    MEM_BYTE  -> "h00000000000000ff".U,
    MEM_HALF  -> "h000000000000ffff".U,
    MEM_WORD  -> "h00000000ffffffff".U,
    MEM_DWORD -> "hffffffffffffffff".U
  ))

  val load_data = Wire(UInt(64.W))
  val load_data_reg = RegNext(load_data)

  // may need to read/write memory in 2 lines
  val stall = RegInit(false.B)
  // half  -> offset = 111
  // word  -> offset = 101/110/111
  // dword -> offset != 000
  stall := Mux(uop.fu_code === FU_MEM, MuxLookup(uop.mem_size, false.B, Array(
    MEM_HALF  -> (addr_offset === "b111".U),
    MEM_WORD  -> (addr_offset.asUInt() > "b100".U),
    MEM_DWORD -> (addr_offset =/= "b000".U)
  )), false.B)

  when (stall) {
    stall := false.B
  }

  // 0 = normal / read line 1, 1 = read line 2
  val dmem_state = RegInit(0.U(1.W))
  when (dmem_state === 0.U) {
    when (stall) { dmem_state := 1.U }
    io.dmem.addr := addr
    load_data := io.dmem.rdata >> (addr_offset << 3)
    io.dmem.wmask := mask & ((wmask << (addr_offset << 3))(63, 0))
    io.dmem.wdata := (in2 << (addr_offset << 3))(63, 0)
  } .otherwise {
    io.dmem.addr := addr_nextline
    load_data := load_data_reg | (io.dmem.rdata << (addr_offset_nextline << 3))
    io.dmem.wmask := mask_nextline & (wmask >> (addr_offset_nextline << 3)).asUInt()
    io.dmem.wdata := (in2 >> (addr_offset_nextline << 3)).asUInt()
  }

  io.dmem.en := (uop.fu_code === FU_MEM)
  io.dmem.wen := (uop.mem_code === MEM_ST)

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

  io.out := load_out
  io.out_valid := !stall
}

class Csr extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val in1 = Input(UInt(64.W))
    val out = Output(UInt(64.W))
  })

  val uop = io.uop
  val in1 = io.in1
  val is_csr = (uop.csr_code =/= CSR_X)

  val addr = uop.inst(31, 20)
  val rdata = Wire(UInt(64.W))
  val ren = is_csr
  val wdata = Wire(UInt(64.W))
  val wen = is_csr && (in1 =/= 0.U)

  wdata := MuxLookup(uop.csr_code, 0.U, Array(
    CSR_RW -> in1,
    CSR_RS -> (rdata | in1),
    CSR_RC -> (rdata & ~in1)
  ))

  val mcycle = RegInit(0.U(64.W))
  mcycle := mcycle + 1.U
  BoringUtils.addSource(mcycle, "csr_mcycle")

  val csr_map = Map(
    RegMap(Csrs.mcycle, mcycle)
  )

  RegMap.access(csr_map, addr, rdata, ren, wdata, wen)

  io.out := rdata
}

class Execution extends Module with Ext {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp())
    val rs1_data = Input(UInt(64.W))
    val rs2_data = Input(UInt(64.W))
    val out = Output(UInt(64.W))
    val out_valid = Output(Bool())
    val jmp = Output(Bool())
    val jmp_pc = Output(UInt(32.W))
    val dmem = Flipped(new RamIO)
  })

  val uop = io.uop
  val in1_0, in1, in2_0, in2 = Wire(UInt(64.W))

  in1_0 := MuxLookup(uop.rs1_src, 0.U, Array(
    RS_FROM_RF  -> io.rs1_data,
    RS_FROM_IMM -> SignExt32_64(uop.imm),
    RS_FROM_PC  -> ZeroExt32_64(uop.pc),
    RS_FROM_NPC -> ZeroExt32_64(uop.npc)
  )).asUInt()

  in2_0 := MuxLookup(uop.rs2_src, 0.U, Array(
    RS_FROM_RF  -> io.rs2_data,
    RS_FROM_IMM -> SignExt32_64(uop.imm),
    RS_FROM_PC  -> ZeroExt32_64(uop.pc),
    RS_FROM_NPC -> ZeroExt32_64(uop.npc)
  )).asUInt()

  in1 := Mux(uop.w_type, Mux(uop.alu_code === ALU_SRL, ZeroExt32_64(in1_0(31, 0)), SignExt32_64(in1_0(31, 0))), in1_0)
  in2 := Mux(uop.w_type, SignExt32_64(in2_0(31, 0)), in2_0)

  val alu = Module(new Alu)
  alu.io.uop := uop
  alu.io.in1 := in1
  alu.io.in2 := in2

  val lsu = Module(new Lsu)
  lsu.io.uop := uop
  lsu.io.in1 := in1
  lsu.io.in2 := in2
  lsu.io.dmem <> io.dmem

  val csr = Module(new Csr)
  csr.io.uop := uop
  csr.io.in1 := in1

  io.out := alu.io.out | lsu.io.out | csr.io.out
  io.out_valid := lsu.io.out_valid
  io.jmp := alu.io.jmp
  io.jmp_pc := alu.io.jmp_pc
}
