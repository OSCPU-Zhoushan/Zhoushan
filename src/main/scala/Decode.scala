package zhoushan

import chisel3._
import chisel3.util._
import zhoushan.Constant._
import zhoushan.Instructions._

  // val rd_en = Bool()
  // val rs1_src = UInt(3.W)
  // val rs2_src = UInt(3.W)
  // val fu_code = UInt(2.W)
  // val alu_code = UInt(4.W)

class Decode extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(32.W))
    val inst = Input(UInt(32.W))
    val uop = Output(new MicroOp())
  })

  val inst = io.inst
  val uop = io.uop

  uop.pc := io.pc
  uop.inst := inst
  
  uop.rs1_addr := inst(19, 15)
  uop.rs2_addr := inst(24, 20)
  uop.rd_addr := inst(11, 7)
  
  val ctrl = ListLookup(inst,
                //   v  fu_code alu_code  rs1_src     rs2_src   rd_en imm_type  
                List(N, FU_X,   ALU_X,    RS_X,       RS_X,        N, IMM_X ), 
    Array(
      // RV32I
      // LUI
      // AUIPC
      // JAL
      // JALR
      // BEQ
      // BNE
      // BLT
      // BGE
      // BLTU
      // BGEU
      // LB
      // LH
      // LW
      // LBU
      // LHU
      // SB
      // SH
      // SW
      ADDI  ->  List(Y, FU_ALU, ALU_ADD,  RS_FROM_RF, RS_FROM_IMM, Y, IMM_I ),
      // SLTI
      // SLTIU
      XORI  ->  List(Y, FU_ALU, ALU_XOR,  RS_FROM_RF, RS_FROM_IMM, Y, IMM_I ),
      ORI   ->  List(Y, FU_ALU, ALU_OR,   RS_FROM_RF, RS_FROM_IMM, Y, IMM_I ),
      ANDI  ->  List(Y, FU_ALU, ALU_AND,  RS_FROM_RF, RS_FROM_IMM, Y, IMM_I ),
      // SLLI
      // SRLI
      // SRAI
      ADD   ->  List(Y, FU_ALU, ALU_ADD,  RS_FROM_RF, RS_FROM_RF,  Y, IMM_I ),
      SUB   ->  List(Y, FU_ALU, ALU_SUB,  RS_FROM_RF, RS_FROM_RF,  Y, IMM_I ),
      // SLL
      // SLT
      // SLTU
      XOR   ->  List(Y, FU_ALU, ALU_XOR,  RS_FROM_RF, RS_FROM_RF,  Y, IMM_I ),
      // SRL
      // SRA
      OR    ->  List(Y, FU_ALU, ALU_OR,   RS_FROM_RF, RS_FROM_RF,  Y, IMM_I ),
      AND   ->  List(Y, FU_ALU, ALU_AND,  RS_FROM_RF, RS_FROM_RF,  Y, IMM_I )
      // FENCE
      // ECALL
      // EBREAK
      // RV64I
      // LWU
      // LD
      // SD
      // ADDIW
      // SLLIW
      // SRLIW
      // SRAIW
      // ADDW
      // SUBW
      // SLLW
      // SRLW
      // SRAW
    )
  )
  val (valid : Bool) :: fu_code :: alu_code :: rs1_src :: rs2_src :: (rd_en : Bool) :: imm_type :: Nil = ctrl
  uop.valid := valid
  uop.fu_code := fu_code
  uop.alu_code := alu_code
  uop.rs1_src := rs1_src
  uop.rs2_src := rs2_src
  uop.rd_en := rd_en

  val imm_i = Cat(Fill(21, inst(31)), inst(30, 20))
  val imm_s = Cat(Fill(21, inst(31)), inst(30, 25), inst(11, 7))
  val imm_b = Cat(Fill(20, inst(31)), inst(7), inst(30, 25), inst(11, 8), 0.U)
  val imm_u = Cat(inst(31, 12), Fill(12, 0.U))
  val imm_j = Cat(Fill(12, inst(31)), inst(19, 12), inst(20), inst(30, 21), 0.U)
  val imm_shamt = Cat(Fill(27, 0.U), inst(24, 20))

  uop.imm := MuxLookup(imm_type, 0.U(32.W), Array(
    IMM_I -> imm_i,
    IMM_S -> imm_s,
    IMM_B -> imm_b,
    IMM_U -> imm_u,
    IMM_J -> imm_j,
    IMM_SHAMT -> imm_shamt
  ))

}
