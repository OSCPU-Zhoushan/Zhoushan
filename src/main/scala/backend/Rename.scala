package zhoushan

import chisel3._
import chisel3.util._

// One-way rename for scalar pipeline
// PRF size = 64

trait RegStateConstant {
  val FREE      = 0.asUInt(2.W)
  val MAPPED    = 1.asUInt(2.W)
  val EXECUTED  = 2.asUInt(2.W)
  val COMMITTED = 3.asUInt(2.W)
}

class RegStateTable extends Module with RegStateConstant {
  val io = IO(new Bundle {
    val w1_map_req = Input(Bool())
    val w1_map_paddr = Output(UInt(6.W))
    val w1_exe_paddr = Input(UInt(6.W))
    val w2_exe_paddr = Input(UInt(6.W))
    val w1_cm_paddr = Input(UInt(6.W))
    val w1_free_paddr = Input(UInt(6.W))
    val allocatable = Output(Bool())
  })

  val table = RegInit(VecInit(Seq.fill(32)(COMMITTED) ++ Seq.fill(32)(FREE)))
  val free_list = table.map(_ === FREE)
  val free_count = PopCount(free_list)

  val fl1 = free_list
  val w1_map_paddr = Mux(io.w1_map_req, PriorityEncoder(fl1), 0.U)
  table(w1_map_paddr) := Mux(io.w1_map_req, MAPPED, FREE)
  
  // val fl2 = fl1 & ~UIntToOH(w1_map_paddr, 64)
  // val map_r2_addr = Mux(io.map_r2_req, PriorityEncoder(fl2), 0.U)
  // table(map_r2_addr) := Mux(io.map_r2_req, MAPPED, FREE)

  when (io.w1_exe_paddr =/= 0.U) {
    table(io.w1_exe_paddr) := EXECUTED
  }
  when (io.w2_exe_paddr =/= 0.U) {
    table(io.w2_exe_paddr) := EXECUTED
  }
  
  when (io.w1_cm_paddr =/= 0.U) {
    table(io.w1_cm_paddr) := COMMITTED
  }

  when (io.w1_free_paddr =/= 0.U) {
    table(io.w1_free_paddr) := FREE
  }

  // default 
  table(0) := COMMITTED

  io.w1_map_paddr := w1_map_paddr

  io.allocatable := (free_count =/= 0.U)

}

class RenameTable extends Module {
  val io = IO(new Bundle {
    val recover = Input(Bool())
    val w1_rs1_addr = Input(UInt(5.W))
    val w1_rs2_addr = Input(UInt(5.W))
    val w1_rs1_paddr = Output(UInt(6.W))
    val w1_rs2_paddr = Output(UInt(6.W))
    val w1_rd_addr = Input(UInt(5.W))
    val w1_rd_paddr = Input(UInt(6.W))
    val w1_cm_addr = Input(UInt(5.W))
    val w1_free_paddr = Output(UInt(6.W))
  })

  val spec_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(6.W))))
  val arch_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(6.W))))

  when (io.recover) {
    spec_table := arch_table
  }

  io.w1_rs1_paddr := spec_table(io.w1_rs1_addr)
  io.w1_rs2_paddr := spec_table(io.w1_rs2_addr)

  when (io.w1_rd_addr =/= 0.U) {
    spec_table(io.w1_rd_addr) := io.w1_rd_paddr
  }

  io.w1_free_paddr := 0.U
  when (io.w1_cm_addr =/= 0.U) {
    io.w1_free_paddr := arch_table(io.w1_cm_addr)
    arch_table(io.w1_cm_addr) := spec_table(io.w1_cm_addr)
  }

}

class Rename extends Module {
  val io = IO(new Bundle {
    
  })

}
