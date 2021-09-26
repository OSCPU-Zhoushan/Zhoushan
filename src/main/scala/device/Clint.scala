package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._

class Clint extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
  })

  val mtime = RegInit(UInt(64.W), 0.U)
  val mtimecmp = RegInit(UInt(64.W), 0.U)

  // Suppose the unit of mtime is us, core frequency is 100 MHz.
  // 1 us / 100 MHz = 100 = 99 + 1
  val clint_freq = RegInit(UInt(16.W), 100.U)
  val clint_step = RegInit(UInt(16.W), 1.U)

  val counter = RegInit(UInt(16.W), 0.U)
  val counter_next = counter + 1.U
  counter := Mux(counter_next < clint_freq, counter_next, 0.U)
  when (counter_next === clint_freq) {
    mtime := mtime + clint_step
  }

  // ref: RT-Thread & NutShell
  val clint_map = Map(
    RegMap("h4000".U, mtimecmp),
    RegMap("h8000".U, clint_freq),
    RegMap("h8008".U, clint_step),
    RegMap("hbff8".U, mtime)
  )

  val addr = io.in.req.bits.addr(15, 0)
  val rdata = WireInit(UInt(64.W), 0.U)
  val ren = io.in.req.bits.ren & io.in.req.fire()
  val wdata = io.in.req.bits.wdata
  val wmask = MaskExpand(io.in.req.bits.wmask)
  val wen = io.in.req.bits.wen & io.in.req.fire()

  RegMap.access(clint_map, addr, rdata, ren, wdata, wmask, wen)

  val reg_rdata = RegInit(UInt(64.W), 0.U)
  val reg_id = RegInit(UInt(io.in.req.bits.id.getWidth.W), 0.U)
  val s_idle :: s_resp_r :: s_resp_w :: Nil = Enum(3)
  val state = RegInit(s_idle)

  switch (state) {
    is (s_idle) {
      when (ren) {
        reg_rdata := rdata
        reg_id := io.in.req.bits.id
        state := s_resp_r
      } .elsewhen (wen) {
        reg_id := io.in.req.bits.id
        state := s_resp_w
      }
    }
    is (s_resp_r) {
      when (io.in.resp.fire()) {
        state := s_idle
      }
    }
    is (s_resp_w) {
      when (io.in.resp.fire()) {
        state := s_idle
      }
    }
  }

  io.in.req.ready := (state === s_idle)
  io.in.resp.valid := (state =/= s_idle)
  io.in.resp.bits.rdata := reg_rdata
  io.in.resp.bits.user := 0.U
  io.in.resp.bits.id := reg_id

  val mtip = WireInit(UInt(1.W), 0.U)
  mtip := (mtime >= mtimecmp).asUInt()
  BoringUtils.addSource(mtip, "csr_mip_mtip")

  // debug info
  if (ZhoushanConfig.DebugClint) {
    when (mtime =/= RegNext(mtime)) {
      printf("%d: [CLINT] mtime=%d mtimecmp=%d mtip=%x\n", DebugTimer(), mtime, mtimecmp, mtip)
    }
  }

}
