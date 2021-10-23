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
import difftest._

class SimTop extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
    val memAXI_0 = new AxiIO
  })

  val core = Module(new Core)

  val icache = Module(new CacheController(new CacheBusWithUserIO, InstCacheId, InstUncacheId))
  icache.io.in <> core.io.imem

  val dcache = Module(new CacheController(new CacheBusIO, DataCacheId, DataUncacheId))
  dcache.io.in <> core.io.dmem

  val crossbar = Module(new CoreBusCrossbarNto1(4))
  crossbar.io.in(0) <> icache.io.out_cache
  crossbar.io.in(1) <> dcache.io.out_cache
  crossbar.io.in(2) <> icache.io.out_uncache
  crossbar.io.in(3) <> dcache.io.out_uncache

  val core2axi = Module(new CoreBus2Axi(new AxiIO))
  core2axi.in <> crossbar.io.out
  core2axi.out <> io.memAXI_0

  io.uart.out.valid := false.B
  io.uart.out.ch := 0.U
  io.uart.in.valid := false.B

}
