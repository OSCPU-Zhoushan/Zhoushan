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

class InstBundle extends Bundle {
  val pc = Output(UInt(32.W))
  val pred_br = Output(Bool())
  val pred_pc = Output(UInt(32.W))
}

class InstFetch extends InstFetchModule {
  val io = IO(new InstFetchIO)

  val req = io.imem.req
  val resp = io.imem.resp

  val stall = io.stall

  val pc_init = "h80000000".U(32.W)
  val pc_next = WireInit(0.U)
  dontTouch(pc_next)
  val pc = RegInit(pc_init)

  val bp = Module(new BrPredictor)

  bp.io.pc := pc
  bp.io.jmp_packet <> io.jmp_packet

  val mis = io.jmp_packet.mis
  val mis_pc = Mux(io.jmp_packet.jmp, io.jmp_packet.jmp_pc, io.jmp_packet.inst_pc + 4.U)
  val reg_mis = RegInit(false.B)
  val reg_mis_pc = RegInit(UInt(32.W), 0.U)
  val reg_mis_ok = RegInit(false.B)
  when (mis && !resp.fire()) {
    reg_mis := true.B
    reg_mis_pc := mis_pc
    reg_mis_ok := false.B
  }

  val invalid_count = RegInit(0.U(2.W))

  // request queue
  val queue = Module(new Queue(gen = new InstBundle, entries = 2, pipe = true, flow = true))
  queue.io.enq.valid := req.fire()
  queue.io.enq.bits.pc := pc
  queue.io.enq.bits.pred_br := bp.io.pred_br
  queue.io.enq.bits.pred_pc := bp.io.pred_pc
  queue.io.deq.ready := resp.fire()

  when (queue.io.enq.fire()) {
    printf("%d: [FIFO-ENQ ] addr=%x\n", DebugTimer(), queue.io.enq.bits.pc)
  }
  when (queue.io.deq.fire()) {
    printf("%d: [FIFO-DEQ ] addr=%x\n", DebugTimer(), queue.io.deq.bits.pc)
  }

  // handshake signals with I cache
  req.bits.addr := pc
  req.bits.ren := true.B
  req.bits.wdata := 0.U
  req.bits.wmask := 0.U
  req.bits.wen := false.B
  req.valid := !stall && !io.jmp_packet.mis && queue.io.enq.ready

  when (req.fire()) {
    printf("%d: [IF  -REQ ] addr=%x\n", DebugTimer(), req.bits.addr)
  }

  when (resp.fire()) {
    printf("%d: [IF  -RESP] rdata=%x\n", DebugTimer(), resp.bits.rdata)
  }

  resp.ready := !stall

  // update pc when mis or queue.io.enq.fire()
  when (mis || queue.io.enq.fire()) {
    pc := pc_next
    when (queue.io.enq.fire()) {
      reg_mis_ok := true.B
    }
  }

  when (mis) {
    pc_next := mis_pc
    invalid_count := queue.io.count
  } .elsewhen (reg_mis) {
    pc_next := reg_mis_pc
  } .otherwise {
    pc_next := bp.io.pred_pc
  }

  // update inst when queue.io.deq.fire()
  io.out.pc := 0.U
  io.out.inst := 0.U
  io.out.valid := false.B
  io.out.pred_br := false.B
  io.out.pred_pc := 0.U
  when (queue.io.deq.fire()) {
    io.out.pc := queue.io.deq.bits.pc
    io.out.inst := Mux(io.out.pc(2), resp.bits.rdata(63, 32), resp.bits.rdata(31, 0))
    io.out.pred_br := queue.io.deq.bits.pred_br
    io.out.pred_pc := queue.io.deq.bits.pred_pc
    when (mis) {
      io.out.valid := false.B
      invalid_count := invalid_count - 1.U
    } .elsewhen (reg_mis) {
      when (invalid_count =/= 0.U) {
        io.out.valid := false.B
        invalid_count := invalid_count - 1.U
      } .otherwise {
        io.out.valid := true.B
        reg_mis := false.B
      }
    } .otherwise {
      io.out.valid := true.B
    }
  }

  when (io.out.valid) {
    printf("%d: [IF  -OUT ] pc=%x inst=%x\n", DebugTimer(), io.out.pc, io.out.inst)
  }

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