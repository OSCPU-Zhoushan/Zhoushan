package zhoushan

import chisel3._
import chisel3.util._

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
  val sys_code  = UInt(3.W)
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
