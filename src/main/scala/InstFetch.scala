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

  val s_init :: s_idle :: s_req :: s_wait :: s_wait_mis :: Nil = Enum(5)
  val state = RegInit(s_init)

  val req = io.imem.req
  val resp = io.imem.resp

  val stall = io.stall

  val pc_init = "h80000000".U(32.W)
  val pc = RegInit(pc_init)
  val inst = RegInit(0.U(32.W))
  val bp = Module(new BrPredictor)
  val bp_pred_pc = bp.io.pred_pc

  req.bits.addr := pc
  req.bits.ren := true.B          // read-only imem
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen := false.B
  req.valid := (state === s_req) && !stall && !io.jmp_packet.mis
  
  resp.ready := (state === s_wait) || (state === s_wait_mis)

  /* FSM to handle CoreBus bus status
   *
   *  Simplified FSM digram (no stall signal here)
   *
   *             mis_predict    mis_predict  !resp_success
   *                     ┌─┐  ┌───────────┐ ┌─┐
   *                     │ v  v           │ │ v
   *   ┌────────┐      ┌────────┐      ┌────────┐
   *   │ s_init │ ───> │ s_req  │ ───> │ s_wait │
   *   └────────┘      └────────┘      └────────┘
   *                       ^               │
   *                       │               │ resp_success & (mis_count == 0)
   *                   ┌────────┐          │
   *                   │ s_idle │ <────────┘
   *                   └────────┘
   *
   *  Note 1: When a mis-predict occurs, mis_count += 1
   *  Note 2: stall == 1 -> stop FSM, but don't send request more than once
   *
   */

  switch (state) {
    is (s_init) {
      state := s_req
    }
    is (s_idle) {
      pc := Mux(stall, pc, bp_pred_pc)
      state := Mux(stall, s_idle, s_req)
    }
    is (s_req) {
      when (io.jmp_packet.mis) {
        pc := bp_pred_pc
      } .elsewhen (req.fire()) {
        state := s_wait
      }
    }
    is (s_wait) {
      when (io.jmp_packet.mis) {
        pc := bp_pred_pc
        when (resp.fire()) {
          state := s_req
        } .otherwise {
          state := s_wait_mis
        }
      } .elsewhen (resp.fire()) {
        inst := Mux(pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
        state := s_idle
      }
    }
    is (s_wait_mis) {
      when (resp.fire()) {
        state := s_req
      }
    }
  }

  /* Branch predictor logic */

  bp.io.pc := pc
  bp.io.jmp_packet <> io.jmp_packet

  io.out.pc := Mux(state === s_idle && !stall, pc, 0.U)
  io.out.inst := Mux(state === s_idle && !stall, inst, 0.U)
  io.out.pred_br := bp.io.pred_br
  io.out.pred_pc := bp.io.pred_pc
  io.out.valid := true.B
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
