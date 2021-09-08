package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._
import zhoushan.Instructions._

class Decode extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Vec(DecodeWidth, Flipped(Decoupled(new InstPacket)))
    val backend_ready = Input(Bool())
    val uop = Output(new MicroOp)   // todo: 2-way in the future
  })

  val decoder = for (i <- 0 until DecodeWidth) yield {
    val decoder = Module(new Decoder)
    decoder
  }

  for (i <- 0 until DecodeWidth) {
    decoder(i).io.in <> io.in(i)
    decoder(i).io.backend_ready := io.backend_ready
    io.uop := decoder(i).io.uop
  }
}

class Decoder extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new InstPacket))
    val backend_ready = Input(Bool())
    val uop = Output(new MicroOp)
  })

  io.in.ready := io.backend_ready

  val inst = io.in.bits.inst
  val pred_br = io.in.bits.pred_br
  val pred_pc = io.in.bits.pred_pc
  val uop = Wire(new MicroOp)

  uop.pc := io.in.bits.pc
  uop.npc := io.in.bits.pc + 4.U
  uop.inst := inst
  
  uop.rs1_addr := inst(19, 15)
  uop.rs2_addr := inst(24, 20)
  uop.rd_addr := inst(11, 7)

  uop.pred_br := pred_br
  uop.pred_pc := pred_pc
  
  val ctrl = ListLookup(inst,
                  //   v  fu_code alu_code  jmp_code  mem_code mem_size   csr_code   w  rs1_src       rs2_src  rd_en  imm_type  
                  List(N, FU_X,   ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_X,         RS_X,        N, IMM_X    ), 
    Array(
      // RV32I
      LUI     ->  List(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_ZERO, RS_FROM_IMM, Y, IMM_U    ),
      AUIPC   ->  List(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_PC,   RS_FROM_IMM, Y, IMM_U    ),
      JAL     ->  List(Y, FU_JMP, ALU_X,    JMP_JAL,  MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_PC,   RS_FROM_IMM, Y, IMM_J    ),
      JALR    ->  List(Y, FU_JMP, ALU_X,    JMP_JALR, MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_I    ),
      BEQ     ->  List(Y, FU_JMP, ALU_X,    JMP_BEQ,  MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
      BNE     ->  List(Y, FU_JMP, ALU_X,    JMP_BNE,  MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
      BLT     ->  List(Y, FU_JMP, ALU_X,    JMP_BLT,  MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
      BGE     ->  List(Y, FU_JMP, ALU_X,    JMP_BGE,  MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
      BLTU    ->  List(Y, FU_JMP, ALU_X,    JMP_BLTU, MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
      BGEU    ->  List(Y, FU_JMP, ALU_X,    JMP_BGEU, MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
      LB      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LD,  MEM_BYTE,  CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      LH      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LD,  MEM_HALF,  CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      LW      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LD,  MEM_WORD,  CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      LBU     ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_BYTE,  CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      LHU     ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_HALF,  CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SB      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_BYTE,  CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
      SH      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_HALF,  CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
      SW      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_WORD,  CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
      ADDI    ->  List(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SLTI    ->  List(Y, FU_ALU, ALU_SLT,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SLTIU   ->  List(Y, FU_ALU, ALU_SLTU, JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      XORI    ->  List(Y, FU_ALU, ALU_XOR,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      ORI     ->  List(Y, FU_ALU, ALU_OR,   JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      ANDI    ->  List(Y, FU_ALU, ALU_AND,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SLLI    ->  List(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_SHAMT),
      SRLI    ->  List(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_SHAMT),
      SRAI    ->  List(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_SHAMT),
      ADD     ->  List(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SUB     ->  List(Y, FU_ALU, ALU_SUB,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SLL     ->  List(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SLT     ->  List(Y, FU_ALU, ALU_SLT,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SLTU    ->  List(Y, FU_ALU, ALU_SLTU, JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      XOR     ->  List(Y, FU_ALU, ALU_XOR,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SRL     ->  List(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SRA     ->  List(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      OR      ->  List(Y, FU_ALU, ALU_OR,   JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      AND     ->  List(Y, FU_ALU, ALU_AND,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      // FENCE
      // FENCE_I
      ECALL   ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_ECALL, N, RS_X,         RS_X,        N, IMM_X    ),
      // EBREAK
      MRET    ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_MRET,  N, RS_X,         RS_X,        N, IMM_X    ),
      WFI     ->  List(Y, FU_ALU, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_X,         RS_X,        N, IMM_X    ),
      // RV64I
      LWU     ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_WORD,  CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      LD      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_DWORD, CSR_X,     N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SD      ->  List(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_DWORD, CSR_X,     N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
      ADDIW   ->  List(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SLLIW   ->  List(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SRLIW   ->  List(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      SRAIW   ->  List(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
      ADDW    ->  List(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SUBW    ->  List(Y, FU_ALU, ALU_SUB,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SLLW    ->  List(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SRLW    ->  List(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      SRAW    ->  List(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     CSR_X,     Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
      // CSR
      CSRRW   ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_RW,    N, RS_FROM_RF,   RS_X,        Y, IMM_X    ),
      CSRRS   ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_RS,    N, RS_FROM_RF,   RS_X,        Y, IMM_X    ),
      CSRRC   ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_RC,    N, RS_FROM_RF,   RS_X,        Y, IMM_X    ),
      CSRRWI  ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_RW,    N, RS_FROM_IMM,  RS_X,        Y, IMM_CSR  ),
      CSRRSI  ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_RS,    N, RS_FROM_IMM,  RS_X,        Y, IMM_CSR  ),
      CSRRCI  ->  List(Y, FU_CSR, ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_RC,    N, RS_FROM_IMM,  RS_X,        Y, IMM_CSR  ),
      // am
      HALT    ->  List(Y, FU_X,   ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_X,         RS_X,        N, IMM_X    ),
      PUTCH   ->  List(Y, FU_X,   ALU_X,    JMP_X,    MEM_X,   MEM_X,     CSR_X,     N, RS_X,         RS_X,        N, IMM_X    )
    )
  )
  val (valid : Bool)  :: fu_code :: alu_code :: jmp_code       :: mem_code :: mem_size :: csr_code :: c0 = ctrl
  val (w_type : Bool) :: rs1_src :: rs2_src  :: (rd_en : Bool) :: imm_type :: Nil                        = c0

  uop.valid := valid
  uop.fu_code := fu_code
  uop.alu_code := alu_code
  uop.jmp_code := jmp_code
  uop.mem_code := mem_code
  uop.mem_size := mem_size
  uop.csr_code := csr_code
  uop.w_type := w_type
  uop.rs1_src := rs1_src
  uop.rs2_src := rs2_src
  uop.rd_en := rd_en

  uop.rs1_paddr := 0.U
  uop.rs2_paddr := 0.U
  uop.rd_paddr := 0.U
  uop.rob_addr := 0.U

  val imm_i = Cat(Fill(21, inst(31)), inst(30, 20))
  val imm_s = Cat(Fill(21, inst(31)), inst(30, 25), inst(11, 7))
  val imm_b = Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U)
  val imm_u = Cat(inst(31, 12), Fill(12, 0.U))
  val imm_j = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U)
  val imm_shamt = Mux(w_type, Cat(Fill(27, 0.U), inst(24, 20)), Cat(Fill(26, 0.U), inst(25, 20)))
  val imm_csr = Cat(Fill(27, 0.U), inst(19, 15))

  uop.imm := MuxLookup(imm_type, 0.U(32.W), Array(
    IMM_I -> imm_i,
    IMM_S -> imm_s,
    IMM_B -> imm_b,
    IMM_U -> imm_u,
    IMM_J -> imm_j,
    IMM_SHAMT -> imm_shamt,
    IMM_CSR -> imm_csr
  ))

  io.uop := Mux(io.in.valid, uop, 0.U.asTypeOf(new MicroOp))

}
