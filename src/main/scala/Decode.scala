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

  val uop = io.uop

  uop.pc := io.pc
  uop.inst := io.inst
  
  uop.rs1_addr := 0.U(5.W)
  uop.rs2_addr := 0.U(5.W)
  uop.rd_addr := 0.U(5.W)
  uop.imm := 0.U(64.W)
  
  val ctrl = ListLookup(io.inst,
                //   v  fu_code alu_code  rs1_src     rs2_src     rd_en   
                List(N, FU_X,   ALU_X,    RS_X,       RS_X      , N     ), 
    Array(
      ADDI  ->  List(Y, FU_ALU, ALU_ADD,  RS_FROM_RF, RS_FROM_RF, Y     )
    )
  )
  val (valid : Bool) :: fu_code :: alu_code :: rs1_src :: rs2_src :: (rd_en : Bool) :: Nil = ctrl
  uop.valid := valid
  uop.fu_code := fu_code
  uop.alu_code := alu_code
  uop.rs1_src := rs1_src
  uop.rs2_src := rs2_src
  uop.rd_en := rd_en
  
}