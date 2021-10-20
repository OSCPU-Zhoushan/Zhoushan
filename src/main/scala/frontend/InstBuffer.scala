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

class InstBuffer extends Module with ZhoushanConfig {
  val entries = InstBufferSize
  val enq_width = FetchWidth
  val deq_width = DecodeWidth

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new InstPacketVec(enq_width)))
    val out = Decoupled(new InstPacketVec(deq_width))
    val flush = Input(Bool())
  })

  /* InstBuffer (based on a circular queue)
   *
   *   Case 1 (Size = 16, Count = 5)
   *   0              f
   *   ------XXXXX-----
   *         ^    ^
   *         |    enq_ptr = 0xb (flag = 0)
   *         deq_ptr = 0x6 (flag = 0)
   *
   *   enq_vec = (0x0b, 0x0c)
   *   deq_vec = (0x06, 0x07)
   *
   *
   *   Case 2 (Size = 16, Count = 7)
   *   0              f
   *   XXX---------XXXX
   *      ^        ^
   *      |        deq_ptr = 0xc (flag = 0)
   *      enq_ptr = 0x3 (flag = 1)
   *
   *   enq_vec = (0x13, 0x14)
   *   deq_vec = (0x0c, 0x0d)
   *
   */

  val idx_width = log2Up(entries)
  val addr_width = idx_width + 1  // MSB is flag bit
  def getIdx(x: UInt): UInt = x(idx_width - 1, 0)
  def getFlag(x: UInt): Bool = x(addr_width - 1).asBool()

  val buf = SyncReadMem(entries, new InstPacket, SyncReadMem.WriteFirst)

  val enq_vec = RegInit(VecInit((0 until enq_width).map(_.U(addr_width.W))))
  val deq_vec = RegInit(VecInit((0 until deq_width).map(_.U(addr_width.W))))
  val enq_ptr = getIdx(enq_vec(0))
  val deq_ptr = getIdx(deq_vec(0))
  val enq_flag = getFlag(enq_vec(0))
  val deq_flag = getFlag(deq_vec(0))

  val count = Mux(enq_flag === deq_flag, enq_ptr - deq_ptr, entries.U + enq_ptr - deq_ptr)
  val enq_ready = RegInit(true.B)

  val num_enq = Mux(io.in.fire(), PopCount(io.in.bits.vec.map(_.valid)), 0.U)
  val num_deq = Mux(io.out.fire(), PopCount(io.out.bits.vec.map(_.valid)), 0.U)

  val num_try_deq = Mux(count >= deq_width.U, deq_width.U, count)
  val num_after_enq = count +& num_enq
  val next_valid_entry = Mux(io.out.ready, num_after_enq - num_try_deq, num_after_enq)

  enq_ready := (entries - enq_width).U >= next_valid_entry

  // enq

  val offset = Wire(Vec(enq_width, UInt(log2Up(enq_width).W)))
  for (i <- 0 until enq_width) {
    if (i == 0) {
      offset(i) := 0.U
    } else {
      // todo: currently only support 2-way
      offset(i) := PopCount(io.in.bits.vec(0).valid)
    }
  }

  for (i <- 0 until enq_width) {
    val enq = Wire(new InstPacket)
    enq := io.in.bits.vec(i)

    when (io.in.bits.vec(i).valid && io.in.fire() && !io.flush) {
      val enq_addr = Wire(UInt(idx_width.W))
      enq_addr := getIdx(enq_vec(offset(i)))
      buf.write(enq_addr, enq)
    }
  }

  val next_enq_vec = VecInit(enq_vec.map(_ + num_enq))

  when (io.in.fire() && !io.flush) {
    enq_vec := next_enq_vec
  }

  io.in.ready := enq_ready

  // deq

  val valid_vec = Mux(count >= deq_width.U, ((1 << deq_width) - 1).U, UIntToOH(count, deq_width) - 1.U)
  val next_deq_vec = VecInit(deq_vec.map(_ + num_deq))
  deq_vec := next_deq_vec

  for (i <- 0 until deq_width) {
    val deq = buf.read(getIdx(next_deq_vec(i)))
    io.out.bits.vec(i) := deq
    io.out.bits.vec(i).valid := valid_vec(i)
  }

  io.out.valid := Cat(io.out.bits.vec.map(_.valid)).orR

  // flush

  when (io.flush) {
    enq_ready := true.B
    enq_vec := VecInit((0 until enq_width).map(_.U(addr_width.W)))
    deq_vec := VecInit((0 until deq_width).map(_.U(addr_width.W)))
  }

  // reset

  when (reset.asBool()) {
    for (i <- 0 until entries) {
      buf.write(i.U, 0.U.asTypeOf(new InstPacket))
    }
  }

  // debug

  if (EnableDifftest && EnableQueueAnalyzer) {
    val queue_ib_count = count
    BoringUtils.addSource(queue_ib_count, "profile_queue_ib_count")
  }

  if (DebugInstBuffer) {
    when (io.in.valid) {
      for (i <- 0 until FetchWidth) {
        val in = io.in.bits.vec(i)
        printf("%d: [IB  %d] pc=%x inst=%x v=%x\n", DebugTimer(), i.U,
               in.pc, in.inst, in.valid)
      }
    }
  }

}
