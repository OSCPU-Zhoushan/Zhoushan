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
import chisel3.util.experimental._

class Clint extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
  })

  val mtime = RegInit(UInt(64.W), 0.U)
  val mtimecmp = RegInit(UInt(64.W), 0.U)

  // Suppose the unit of mtime is us, core frequency is 100 MHz.
  // 1 us / 100 MHz = 100
  val clint_freq = RegInit(UInt(64.W), 1.U)
  val clint_step = RegInit(UInt(64.W), 1.U)

  val counter = RegInit(UInt(64.W), 0.U)
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
  val wdata = io.in.req.bits.wdata
  val wmask = MaskExpand(io.in.req.bits.wmask)
  val wen = io.in.req.bits.wen

  RegMap.access(clint_map, addr, rdata, wdata, wmask, wen && io.in.req.fire())

  val reg_rdata = RegInit(UInt(64.W), 0.U)
  val reg_id = RegInit(UInt(io.in.req.bits.id.getWidth.W), 0.U)

  // state machine to handle data req & resp
  val s_idle :: s_wait :: Nil = Enum(2)
  val state = RegInit(s_idle)

  switch (state) {
    is (s_idle) {
      when (io.in.req.fire()) {
        when (wen) {
          reg_id := io.in.req.bits.id
        } .otherwise {
          reg_rdata := rdata
          reg_id := io.in.req.bits.id
        }
        state := s_wait
      }
    }
    is (s_wait) {
      when (io.in.resp.fire()) {
        state := s_idle
      }
    }
  }

  io.in.req.ready := (state === s_idle)
  io.in.resp.valid := (state === s_wait)
  io.in.resp.bits.rdata := reg_rdata
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
