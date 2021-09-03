package zhoushan

import chisel3._
import chisel3.util._

abstract class AbstractInstFetchIO extends Bundle {
  val imem : MemIO
  val jmp_packet = Input(new JmpPacket)
  val stall = Input(Bool())
  val out = Output(new InstPacket)
}

class InstFetchIO extends AbstractInstFetchIO {
  override val imem = new CacheBusIO
}

class InstFetchWithRamHelperIO extends AbstractInstFetchIO {
  override val imem = Flipped(new RomIO)
}

abstract class InstFetchModule extends Module {
  val io : Bundle
}

class InstFetch extends InstFetchModule {
  val io = IO(new InstFetchIO)

  val req = io.imem.req
  val resp = io.imem.resp

  val stall = io.stall
  val reg_stall = RegNext(stall)

  val pc_init = "h80000000".U(32.W)
  val pc_next = RegInit(pc_init)
  val pc = RegInit(pc_init)
  val pc_valid = RegInit(false.B)

  val inst = WireInit(UInt(32.W), 0.U)
  val inst_valid = WireInit(false.B)

  val bp = Module(new BrPredictor)
  val bp_pred_pc = bp.io.pred_pc
  val pred_br = RegInit(false.B)
  val pred_pc = RegInit(UInt(32.W), 0.U)

  val mis = io.jmp_packet.mis
  val mis_pc = Mux(io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.inst_pc + 4.U)
  val reg_mis = RegInit(false.B)
  val reg_mis_pc = RegInit(UInt(32.W), 0.U)
  when (io.jmp_packet.mis && !resp.fire()) {
    reg_mis := true.B
    reg_mis_pc := mis_pc
  }

  req.bits.addr := pc_next
  req.bits.ren := true.B          // read-only imem
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen := false.B
  req.valid := !stall && !io.jmp_packet.mis
  
  resp.ready := !stall
  
  /* Branch predictor logic */

  bp.io.pc := pc_next
  bp.io.jmp_packet <> io.jmp_packet

  // update pc when req.fire()

  when (req.fire() || io.jmp_packet.mis) {
    pc := pc_next
    pred_br := bp.io.pred_br
    pred_pc := bp.io.pred_pc
    pc_valid := true.B
    when (io.jmp_packet.mis) {
      pc_next := mis_pc
      pc_valid := false.B
    } .elsewhen (reg_mis) {
      pc_next := reg_mis_pc
      pc_valid := false.B
      reg_mis := false.B
    } .otherwise {
      pc_next := bp_pred_pc
    }
  }

  // update inst when 

  inst_valid := false.B
  when (resp.fire()) {
    inst := Mux(pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
    inst_valid := !reg_mis && pc_valid
  }

  io.out.pc := pc
  io.out.inst := inst
  io.out.valid := inst_valid

  io.out.pred_br := pred_br
  io.out.pred_pc := pred_pc

}

class InstFetchWithRamHelper extends InstFetchModule {
  val io = IO(new InstFetchWithRamHelperIO)

  val pc_init = "h80000000".U(32.W)
  val pc = RegInit(pc_init)
  val inst = io.imem.rdata(31, 0)

  io.imem.en := true.B
  io.imem.addr := pc.asUInt()

  val bp = Module(new BrPredictor)
  bp.io.pc := pc
  bp.io.jmp_packet <> io.jmp_packet

  val pc_zero_reset = RegInit(true.B) // todo: fix pc reset
  pc_zero_reset := false.B
  pc := Mux(pc_zero_reset, pc_init,
        Mux(io.stall, pc, bp.io.pred_pc))

  io.out.pc := pc
  io.out.inst := inst
  io.out.pred_br := bp.io.pred_br
  io.out.pred_pc := bp.io.pred_pc
  io.out.valid := true.B
}
