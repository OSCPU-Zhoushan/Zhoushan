package zhoushan

import chisel3._
import chisel3.util._

trait Constant {
  val Y = true.B
  val N = false.B

  val RS_X          = 0.asUInt(3.W)
  val RS_FROM_RF    = 1.asUInt(3.W)
  val RS_FROM_IMM   = 2.asUInt(3.W)
  val RS_FROM_ZERO  = 3.asUInt(3.W)
  val RS_FROM_PC    = 4.asUInt(3.W)
  val RS_FROM_NPC   = 5.asUInt(3.W)

  val IMM_X     = 0.asUInt(3.W)
  val IMM_I     = 1.asUInt(3.W)
  val IMM_S     = 2.asUInt(3.W)
  val IMM_B     = 3.asUInt(3.W)
  val IMM_U     = 4.asUInt(3.W)
  val IMM_J     = 5.asUInt(3.W)
  val IMM_SHAMT = 6.asUInt(3.W)
  val IMM_CSR   = 7.asUInt(3.W)

  val FU_X      = 0.asUInt(3.W)
  val FU_ALU    = 1.asUInt(3.W)
  val FU_JMP    = 2.asUInt(3.W)
  val FU_MEM    = 3.asUInt(3.W)
  val FU_CSR    = 4.asUInt(3.W)

  val ALU_X     = 0.asUInt(4.W)
  val ALU_ADD   = 1.asUInt(4.W)
  val ALU_SUB   = 2.asUInt(4.W)
  val ALU_SLT   = 3.asUInt(4.W)
  val ALU_SLTU  = 4.asUInt(4.W)
  val ALU_XOR   = 5.asUInt(4.W)
  val ALU_OR    = 6.asUInt(4.W)
  val ALU_AND   = 7.asUInt(4.W)
  val ALU_SLL   = 8.asUInt(4.W)
  val ALU_SRL   = 9.asUInt(4.W)
  val ALU_SRA   = 10.asUInt(4.W)

  val JMP_X     = 0.asUInt(4.W)
  val JMP_JAL   = 1.asUInt(4.W)
  val JMP_JALR  = 2.asUInt(4.W)
  val JMP_BEQ   = 3.asUInt(4.W)
  val JMP_BNE   = 4.asUInt(4.W)
  val JMP_BLT   = 5.asUInt(4.W)
  val JMP_BGE   = 6.asUInt(4.W)
  val JMP_BLTU  = 7.asUInt(4.W)
  val JMP_BGEU  = 8.asUInt(4.W)

  val MEM_X     = 0.asUInt(2.W)
  val MEM_LD    = 1.asUInt(2.W)
  val MEM_LDU   = 2.asUInt(2.W)
  val MEM_ST    = 3.asUInt(2.W)

  val MEM_BYTE  = 0.asUInt(2.W)
  val MEM_HALF  = 1.asUInt(2.W)
  val MEM_WORD  = 2.asUInt(2.W)
  val MEM_DWORD = 3.asUInt(2.W)

  val CSR_X     = 0.asUInt(3.W)
  val CSR_RW    = 1.asUInt(3.W)
  val CSR_RS    = 2.asUInt(3.W)
  val CSR_RC    = 3.asUInt(3.W)
  val CSR_ECALL = 4.asUInt(3.W)
  val CSR_MRET  = 5.asUInt(3.W)
}

object Constant extends Constant { }

class MicroOp extends Bundle {
  val valid     = Bool()

  val pc        = UInt(32.W)
  val npc       = UInt(32.W)
  val inst      = UInt(32.W)

  val fu_code   = UInt(3.W)
  val alu_code  = UInt(4.W)
  val jmp_code  = UInt(4.W)
  val mem_code  = UInt(2.W)
  val mem_size  = UInt(2.W)
  val csr_code  = UInt(3.W)
  val w_type    = Bool()

  val rs1_src   = UInt(3.W)
  val rs2_src   = UInt(3.W)

  val rs1_addr  = UInt(5.W)
  val rs2_addr  = UInt(5.W)
  val rd_addr   = UInt(5.W)
  val rd_en     = Bool()

  val imm       = UInt(32.W)

  // branch prediction related
  val pred_br   = Bool()
  val pred_bpc  = UInt(32.W)

  // register renaming related
  val rs1_paddr = UInt(log2Up(ZhoushanConfig.PrfSize).W)   // rs1 prf addr
  val rs2_paddr = UInt(log2Up(ZhoushanConfig.PrfSize).W)   // rs2 prf addr
  val rd_paddr  = UInt(log2Up(ZhoushanConfig.PrfSize).W)   // rd prf addr
  val rd_ppaddr = UInt(log2Up(ZhoushanConfig.PrfSize).W)   // rd prev prf addr

  // re-order buffer related
  val rob_addr  = UInt(log2Up(ZhoushanConfig.RobSize).W)
}

object RasConstant {
  val RAS_X             = 0.asUInt(2.W)
  val RAS_PUSH          = 1.asUInt(2.W)
  val RAS_POP           = 2.asUInt(2.W)
  val RAS_POP_THEN_PUSH = 3.asUInt(2.W)

  def isRasPush(x: UInt): Bool = x(0) === 1.U
  def isRasPop(x: UInt): Bool = x(1) === 1.U
}

class JmpPacket extends Bundle {
  val valid = Bool()
  val inst_pc = UInt(32.W)
  val jmp = Bool()
  val jmp_pc = UInt(32.W)
  val mis = Bool()
  val intr = Bool()
  val ras_type = UInt(2.W)
}
