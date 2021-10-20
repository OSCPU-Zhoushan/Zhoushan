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

class CacheController[BT <: CacheBusIO](bus_type: BT, id_cache: Int, id_uncache: Int) extends Module with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(bus_type)
    val out_cache = new CoreBusIO
    val out_uncache = new CoreBusIO
  })

  val to_uncache = (io.in.req.bits.addr(31) === 0.U)
  val cache = Module(new Cache(bus_type, id_cache))
  val uncache = Module(new Uncache(bus_type, id_uncache))

  val crossbar1to2 = Module(new CacheBusCrossbar1to2(bus_type))
  crossbar1to2.io.to_1 := to_uncache
  crossbar1to2.io.in <> io.in
  crossbar1to2.io.out(0) <> cache.io.in
  crossbar1to2.io.out(1) <> uncache.io.in

  io.out_cache <> cache.io.out
  io.out_uncache <> uncache.io.out

}
