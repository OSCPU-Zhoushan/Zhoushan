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
import zhoushan.Constant._

class Execution extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    // input
    val uop = Input(new MicroOp)
    val rs1_data = Input(UInt(64.W))
    val rs2_data = Input(UInt(64.W))
    // output
    val rd_data = Output(UInt(64.W))
    val busy = Output(Bool())
    val jmp_packet = Output(new JmpPacket)
    // dmem
    val dmem = new CacheBusIO
  })

  val in1_0 = Wire(UInt(64.W))
  val in2_0 = Wire(UInt(64.W))
  val in1 = Wire(UInt(64.W))
  val in2 = Wire(UInt(64.W))

  in1_0 := MuxLookup(io.uop.rs1_src, 0.U, Array(
    s"b$RS_FROM_RF".U  -> io.rs1_data,
    s"b$RS_FROM_IMM".U -> SignExt32_64(io.uop.imm),
    s"b$RS_FROM_PC".U  -> ZeroExt32_64(io.uop.pc)
  ))(63, 0)

  in2_0 := MuxLookup(io.uop.rs2_src, 0.U, Array(
    s"b$RS_FROM_RF".U  -> io.rs2_data,
    s"b$RS_FROM_IMM".U -> SignExt32_64(io.uop.imm),
    s"b$RS_FROM_PC".U  -> ZeroExt32_64(io.uop.pc)
  ))(63, 0)

  in1 := Mux(io.uop.w_type,
             Mux(io.uop.alu_code === s"b$ALU_SRL".U,
                 ZeroExt32_64(in1_0(31, 0)),
                 SignExt32_64(in1_0(31, 0))),
             in1_0)
  in2 := Mux(io.uop.w_type, SignExt32_64(in2_0(31, 0)), in2_0)

  val alu = Module(new Alu)
  alu.io.in1 := in1
  alu.io.in2 := in2

  val csr = Module(new Csr)
  csr.io.in1 := in1

  val lsu = Module(new Lsu)
  lsu.io.in1 := in1
  lsu.io.in2 := in2
  lsu.io.dmem <> io.dmem

  // input and output
  alu.io.uop := 0.U.asTypeOf(new MicroOp)
  csr.io.uop := 0.U.asTypeOf(new MicroOp)
  lsu.io.uop := 0.U.asTypeOf(new MicroOp)

  io.rd_data := 0.U
  io.busy := lsu.io.busy
  io.jmp_packet := 0.U.asTypeOf(new JmpPacket)

  when (io.uop.fu_code === s"b$FU_ALU".U || io.uop.fu_code === s"b$FU_JMP".U) {
    alu.io.uop := io.uop
    io.rd_data := alu.io.out
    io.jmp_packet := alu.io.jmp_packet
  } .elsewhen (io.uop.fu_code === s"b$FU_SYS".U) {
    csr.io.uop := io.uop
    io.rd_data := csr.io.out
    io.jmp_packet := csr.io.jmp_packet
  } .elsewhen (io.uop.fu_code === s"b$FU_MEM".U) {
    lsu.io.uop := io.uop
    io.rd_data := lsu.io.out
  }

}
