package zhoushan

import chisel3._
import chisel3.util._
import chisel3.util.experimental._

class Clint extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
  })

  def maskExpand(x: UInt) = Cat(x.asBools.map(Fill(8, _)).reverse)

  val mtime = RegInit(UInt(64.W), 0.U)
  val mtimecmp = RegInit(UInt(64.W), 0.U)

  // Suppose the unit of mtime is us, core frequency is 100 MHz.
  // 1 us / 100 MHz = 100
  val tick = RegInit(UInt(64.W), 100.U)
  val counter = RegInit(UInt(64.W), 0.U)

  counter := Mux(counter < tick, counter + 1.U, 0.U)
  when (counter === tick) {
    mtime := mtime + 1.U
  }

  // ref: RT-Thread
  val clint_map = Map(
    RegMap("h4000".U, mtimecmp),
    RegMap("hbff8".U, mtime)
  )

  val addr = io.in.req.bits.addr(15, 0)
  val rdata = WireInit(UInt(64.W), 0.U)
  val ren = io.in.req.bits.ren & io.in.req.fire()
  val wdata = io.in.req.bits.wdata
  val wmask = maskExpand(io.in.req.bits.wmask)
  val wen = io.in.req.bits.wen & io.in.req.fire()

  RegMap.access(clint_map, addr, rdata, ren, wdata, wmask, wen)

  val reg_rdata = RegInit(UInt(64.W), 0.U)
  val s_idle :: s_resp_r :: s_resp_w :: Nil = Enum(3)
  val state = RegInit(s_idle)

  switch (state) {
    is (s_idle) {
      when (ren) {
        reg_rdata := rdata
        state := s_resp_r
      } .elsewhen (wen) {
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

  val mtip = WireInit(UInt(1.W), 0.U)
  mtip := (mtime >= mtimecmp).asUInt()
  // BoringUtils.addSource(mtip, "csr_mip_mtip")

}
