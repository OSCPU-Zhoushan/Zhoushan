package zhoushan

import chisel3._
import chisel3.util._

trait PrfStateConstant {
  val FREE      = 0.asUInt(2.W)
  val MAPPED    = 1.asUInt(2.W)
  val EXECUTED  = 2.asUInt(2.W)
  val COMMITTED = 3.asUInt(2.W)
}

class Rename extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MicroOpVec(DecodeWidth)))
    val out = Decoupled(new MicroOpVec(DecodeWidth))
    val avail_list = Output(UInt(64.W))
    val flush = Input(Bool())
    // from commit stage
    val cm_recover = Input(Bool())
    val cm = Flipped(new MicroOpVec(CommitWidth))
    val exe = Flipped(new MicroOpVec(IssueWidth))
  })

  val en = io.out.ready && io.in.valid

  val in_uop = io.in.bits.vec
  val uop = WireInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))
  uop := in_uop

  val pst = Module(new PrfStateTable)
  pst.io.en := en
  for (i <- 0 until DecodeWidth) {
    pst.io.rd_req(i) := in_uop(i).valid && in_uop(i).rd_en
  }
  for (i <- 0 until IssueWidth) {
    pst.io.exe(i) := Mux(io.exe.vec(i).valid && io.exe.vec(i).rd_en, io.exe.vec(i).rd_paddr, 0.U)
  }
  for (i <- 0 until CommitWidth) {
    pst.io.cm(i) := Mux(io.cm.vec(i).valid && io.cm.vec(i).rd_en, io.cm.vec(i).rd_paddr, 0.U)
    pst.io.free(i) := Mux(io.cm.vec(i).valid && io.cm.vec(i).rd_en, io.cm.vec(i).rd_ppaddr, 0.U)
  }
  pst.io.cm_recover := io.cm_recover
  io.avail_list := pst.io.avail_list

  val rt = Module(new RenameTable)
  rt.io.en := en
  for (i <- 0 until DecodeWidth) {
    rt.io.rs1_addr(i) := in_uop(i).rs1_addr
    rt.io.rs2_addr(i) := in_uop(i).rs2_addr
    uop(i).rs1_paddr  := rt.io.rs1_paddr(i)
    uop(i).rs2_paddr  := rt.io.rs2_paddr(i)
    rt.io.rd_addr(i)  := Mux(in_uop(i).valid && in_uop(i).rd_en, in_uop(i).rd_addr, 0.U)
    uop(i).rd_ppaddr  := rt.io.rd_ppaddr(i)
    rt.io.rd_paddr(i) := pst.io.rd_paddr(i)
  }
  rt.io.cm_recover  := io.cm_recover
  for (i <- 0 until CommitWidth) {
    rt.io.cm_rd_addr(i)  := Mux(io.cm.vec(i).valid && io.cm.vec(i).rd_en, io.cm.vec(i).rd_addr, 0.U)
    rt.io.cm_rd_paddr(i) := io.cm.vec(i).rd_paddr
  }

  io.in.ready := pst.io.allocatable

  // pipeline registers

  val out_uop = RegInit(VecInit(Seq.fill(DecodeWidth)(0.U.asTypeOf(new MicroOp))))
  val out_valid = RegInit(false.B)

  when (io.flush) {
    for (i <- 0 until DecodeWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
    }
    out_valid := false.B
  } .elsewhen (io.out.ready && io.in.valid) {
    for (i <- 0 until DecodeWidth) {
      out_uop(i) := Mux(uop(i).valid, uop(i), 0.U.asTypeOf(new MicroOp))
    }
    out_valid := true.B
  } .otherwise {
    for (i <- 0 until DecodeWidth) {
      out_uop(i) := 0.U.asTypeOf(new MicroOp)
    }
    out_valid := false.B
  }
  
  io.out.valid := out_valid
  io.out.bits.vec := out_uop
}

class RenameTable extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val en = Input(Bool())
    // rs1, rs2
    val rs1_addr = Vec(DecodeWidth, Input(UInt(5.W)))
    val rs2_addr = Vec(DecodeWidth, Input(UInt(5.W)))
    val rs1_paddr = Vec(DecodeWidth, Output(UInt(6.W)))
    val rs2_paddr = Vec(DecodeWidth, Output(UInt(6.W)))
    // rd
    val rd_addr = Vec(DecodeWidth, Input(UInt(5.W)))
    val rd_ppaddr = Vec(DecodeWidth, Output(UInt(6.W)))
    val rd_paddr = Vec(DecodeWidth, Input(UInt(6.W)))
    // from commit stage
    val cm_recover = Input(Bool())
    val cm_rd_addr = Vec(CommitWidth, Input(UInt(5.W)))
    val cm_rd_paddr = Vec(CommitWidth, Input(UInt(6.W)))
  })

  val spec_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(6.W))))
  val arch_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(6.W))))

  for (i <- 0 until DecodeWidth) {
    io.rs1_paddr(i) := spec_table(io.rs1_addr(i))
    io.rs2_paddr(i) := spec_table(io.rs2_addr(i))
    io.rd_ppaddr(i) := spec_table(io.rd_addr(i))
  }

  when (io.cm_recover) {
    spec_table := arch_table
  } .otherwise {
    for (i <- 0 until DecodeWidth) {
      when (io.rd_addr(i) =/= 0.U && io.en) {
        spec_table(io.rd_addr(i)) := io.rd_paddr(i)
      }
    }
    for (i <- 0 until CommitWidth) {
      when (io.cm_rd_addr(i) =/= 0.U) {
        arch_table(io.cm_rd_addr(i)) := io.cm_rd_paddr(i)
      }
    }
  }
}

class PrfStateTable extends Module with PrfStateConstant with ZhoushanConfig {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val allocatable = Output(Bool())
    // allocate free physical registers
    val rd_req = Vec(DecodeWidth, Input(Bool()))
    val rd_paddr = Vec(DecodeWidth, Output(UInt(6.W)))
    // update prf state
    val exe = Vec(IssueWidth, Input(UInt(6.W)))
    val cm = Vec(CommitWidth, Input(UInt(6.W)))
    val free = Vec(CommitWidth, Input(UInt(6.W)))
    val cm_recover = Input(Bool())
    // pass avail list to issue unit
    val avail_list = Output(UInt(64.W))
  })

  val table = RegInit(VecInit(Seq.fill(32)(COMMITTED) ++ Seq.fill(32)(FREE)))

  val free_list = Cat(table.map(_ === FREE).reverse)
  val free_count = PopCount(free_list)
  io.allocatable := (free_count >= DecodeWidth.U)

  val avail_list = Cat(table.map(_ === EXECUTED).reverse) | Cat(table.map(_ === COMMITTED).reverse)
  io.avail_list := avail_list

  val fl0 = free_list
  io.rd_paddr(0) := Mux(io.en && io.rd_req(0), PriorityEncoder(fl0), 0.U)

  val fl1 = fl0 & ~UIntToOH(io.rd_paddr(0), 64)
  io.rd_paddr(1) := Mux(io.en && io.rd_req(1), PriorityEncoder(fl1), 0.U)

  for (i <- 0 until DecodeWidth) {
    when (io.en && io.rd_req(i)) {
      table(io.rd_paddr(i)) := MAPPED
    }
  }

  for (i <- 0 until IssueWidth) {
    when (io.exe(i) =/= 0.U) {
      table(io.exe(i)) := EXECUTED
    }
  }
  
  for (i <- 0 until CommitWidth) {
    when (io.cm(i) =/= 0.U) {
      table(io.cm(i)) := COMMITTED
    } 
  }

  for (i <- 0 until CommitWidth) {
    when (io.free(i) =/= 0.U) {
      table(io.free(i)) := FREE
    }
  }

  when (io.cm_recover) {
    for (i <- 0 until 64) {
      when (table(i) =/= COMMITTED) {
        table(i) := FREE
      }
    }
  }

  // default 
  table(0) := COMMITTED

}
