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

trait Constant {
  val Y = "1"
  val N = "0"
  val X = "?"

  val FU_X      = "???"
  val FU_ALU    = "001"
  val FU_JMP    = "010"
  val FU_SYS    = "011"
  val FU_MEM    = "100"

  val ALU_X     = "????"
  val ALU_ADD   = "0001"
  val ALU_SUB   = "0011"
  val ALU_SLT   = "0100"
  val ALU_SLTU  = "0101"
  val ALU_XOR   = "1000"
  val ALU_OR    = "1001"
  val ALU_AND   = "1010"
  val ALU_SLL   = "1100"
  val ALU_SRL   = "1101"
  val ALU_SRA   = "1111"

  val JMP_X     = "????"
  val JMP_JAL   = "0010"
  val JMP_JALR  = "0011"
  val JMP_BEQ   = "0100"
  val JMP_BNE   = "0101"
  val JMP_BLT   = "1000"
  val JMP_BGE   = "1100"
  val JMP_BLTU  = "1001"
  val JMP_BGEU  = "1101"

  val MEM_X     = "??"
  val MEM_LD    = "01"
  val MEM_LDU   = "10"
  val MEM_ST    = "11"

  val MEM_BYTE  = "00"
  val MEM_HALF  = "01"
  val MEM_WORD  = "10"
  val MEM_DWORD = "11"

  val SYS_X      = "???"
  val SYS_CSRRW  = "001"
  val SYS_CSRRS  = "010"
  val SYS_CSRRC  = "011"
  val SYS_ECALL  = "100"
  val SYS_MRET   = "101"
  val SYS_FENCE  = "110"
  val SYS_FENCEI = "111"

  val RS_X         = "??"
  val RS_FROM_ZERO = "00"
  val RS_FROM_RF   = "01"
  val RS_FROM_IMM  = "10"
  val RS_FROM_PC   = "11"

  val IMM_X     = "???"
  val IMM_I     = "001"
  val IMM_S     = "010"
  val IMM_B     = "011"
  val IMM_U     = "100"
  val IMM_J     = "101"
  val IMM_SHAMT = "110"
  val IMM_CSR   = "111"
}

object Constant extends Constant { }
