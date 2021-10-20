/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import zhoushan.Constant._
import zhoushan.Instructions._

object DecodeConfig {
  val decode_default: String = Seq(
    //             v  fu_code alu_code  jmp_code  mem_code mem_size   sys_code    w  rs1_src       rs2_src  rd_en  imm_type
                   N, FU_X,   ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_X,         RS_X,        N, IMM_X
  ).reduce(_ + _)

  val out_width: Int = decode_default.length

  val decode_table: TruthTable =  TruthTable(Map(
    LUI     -> Seq(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_ZERO, RS_FROM_IMM, Y, IMM_U    ),
    AUIPC   -> Seq(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_PC,   RS_FROM_IMM, Y, IMM_U    ),
    JAL     -> Seq(Y, FU_JMP, ALU_X,    JMP_JAL,  MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_PC,   RS_FROM_IMM, Y, IMM_J    ),
    JALR    -> Seq(Y, FU_JMP, ALU_X,    JMP_JALR, MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_I    ),
    BEQ     -> Seq(Y, FU_JMP, ALU_X,    JMP_BEQ,  MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
    BNE     -> Seq(Y, FU_JMP, ALU_X,    JMP_BNE,  MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
    BLT     -> Seq(Y, FU_JMP, ALU_X,    JMP_BLT,  MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
    BGE     -> Seq(Y, FU_JMP, ALU_X,    JMP_BGE,  MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
    BLTU    -> Seq(Y, FU_JMP, ALU_X,    JMP_BLTU, MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
    BGEU    -> Seq(Y, FU_JMP, ALU_X,    JMP_BGEU, MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_B    ),
    LB      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LD,  MEM_BYTE,  SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    LH      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LD,  MEM_HALF,  SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    LW      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LD,  MEM_WORD,  SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    LBU     -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_BYTE,  SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    LHU     -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_HALF,  SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SB      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_BYTE,  SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
    SH      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_HALF,  SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
    SW      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_WORD,  SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
    ADDI    -> Seq(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SLTI    -> Seq(Y, FU_ALU, ALU_SLT,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SLTIU   -> Seq(Y, FU_ALU, ALU_SLTU, JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    XORI    -> Seq(Y, FU_ALU, ALU_XOR,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    ORI     -> Seq(Y, FU_ALU, ALU_OR,   JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    ANDI    -> Seq(Y, FU_ALU, ALU_AND,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SLLI    -> Seq(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_SHAMT),
    SRLI    -> Seq(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_SHAMT),
    SRAI    -> Seq(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_SHAMT),
    ADD     -> Seq(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SUB     -> Seq(Y, FU_ALU, ALU_SUB,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SLL     -> Seq(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SLT     -> Seq(Y, FU_ALU, ALU_SLT,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SLTU    -> Seq(Y, FU_ALU, ALU_SLTU, JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    XOR     -> Seq(Y, FU_ALU, ALU_XOR,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SRL     -> Seq(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SRA     -> Seq(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    OR      -> Seq(Y, FU_ALU, ALU_OR,   JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    AND     -> Seq(Y, FU_ALU, ALU_AND,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    // FENCE not implemented yet
    FENCE_I -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_FENCEI, N, RS_X,         RS_X,        N, IMM_X    ),
    ECALL   -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_ECALL,  N, RS_X,         RS_X,        N, IMM_X    ),
    // EBREAK not implemented yet
    MRET    -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_MRET,   N, RS_X,         RS_X,        N, IMM_X    ),
    WFI     -> Seq(Y, FU_ALU, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_X,         RS_X,        N, IMM_X    ),
    // RV64I
    LWU     -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_WORD,  SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    LD      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_LDU, MEM_DWORD, SYS_X,      N, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SD      -> Seq(Y, FU_MEM, ALU_X,    JMP_X,    MEM_ST,  MEM_DWORD, SYS_X,      N, RS_FROM_RF,   RS_FROM_RF,  N, IMM_S    ),
    ADDIW   -> Seq(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SLLIW   -> Seq(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SRLIW   -> Seq(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    SRAIW   -> Seq(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_IMM, Y, IMM_I    ),
    ADDW    -> Seq(Y, FU_ALU, ALU_ADD,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SUBW    -> Seq(Y, FU_ALU, ALU_SUB,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SLLW    -> Seq(Y, FU_ALU, ALU_SLL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SRLW    -> Seq(Y, FU_ALU, ALU_SRL,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    SRAW    -> Seq(Y, FU_ALU, ALU_SRA,  JMP_X,    MEM_X,   MEM_X,     SYS_X,      Y, RS_FROM_RF,   RS_FROM_RF,  Y, IMM_X    ),
    // CSR
    CSRRW   -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_CSRRW,  N, RS_FROM_RF,   RS_X,        Y, IMM_X    ),
    CSRRS   -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_CSRRS,  N, RS_FROM_RF,   RS_X,        Y, IMM_X    ),
    CSRRC   -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_CSRRC,  N, RS_FROM_RF,   RS_X,        Y, IMM_X    ),
    CSRRWI  -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_CSRRW,  N, RS_FROM_IMM,  RS_X,        Y, IMM_CSR  ),
    CSRRSI  -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_CSRRS,  N, RS_FROM_IMM,  RS_X,        Y, IMM_CSR  ),
    CSRRCI  -> Seq(Y, FU_SYS, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_CSRRC,  N, RS_FROM_IMM,  RS_X,        Y, IMM_CSR  ),
    // abstract machine
    HALT    -> Seq(Y, FU_ALU, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_X,         RS_X,        N, IMM_X    ),
    PUTCH   -> Seq(Y, FU_ALU, ALU_X,    JMP_X,    MEM_X,   MEM_X,     SYS_X,      N, RS_X,         RS_X,        N, IMM_X    )
  ).map({ case (k, v) => k -> BitPat(s"b${v.reduce(_ + _)}") }), BitPat(s"b$decode_default"))
}
