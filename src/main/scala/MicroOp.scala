package zhoushan

import chisel3._
import zhoushan.Constant._

// class CtrlSig() extends Bundle {
  

//   def apply() = {

//   }

//   def apply(ctrl_list: List[Bits]) = {
//     val (cs_valid : Bool) :: (cs_rd_en : Bool) :: cs_rs1_src :: cs_rs2_src :: cs_fu_code :: cs_alu_code :: Nil = ctrl_list
//     valid := cs_valid
//     rd_en := cs_rd_en
//     rs1_src := cs_rs1_src
//     rs2_src := cs_rs2_src
//     fu_code := cs_fu_code
//     alu_code := cs_alu_code
//   }
// }

// object CtrlSig {
//   def apply() = new CtrlSig()
//   def apply(ctrl_list: List[Bits]) = new CtrlSig(ctrl_list)
// }

class MicroOp extends Bundle {
  val valid     = Bool()

  val pc        = UInt(32.W)
  val inst      = UInt(32.W)

  val fu_code   = UInt(2.W)
  val alu_code  = UInt(4.W)

  val rs1_src   = UInt(3.W)
  val rs2_src   = UInt(3.W)

  val rs1_addr  = UInt(5.W)
  val rs2_addr  = UInt(5.W)
  val rd_addr   = UInt(5.W)
  val rd_en     = Bool()

  val imm       = UInt(64.W)
}
