package zhoushan

import chisel3._
import chisel3.util._

/* InstCache configuration
 * 1xxx xxxx xxxx xxxx xxxx xxxx xxxx xxxx
 *                                      __ (2) byte offset
 *                                    __   (2) word offset (4 words in a line)
 *                                 __      (2) sram index (4 srams)
 *                            __ __        (4) set index (16 sets)
 *  ___ ____ ____ ____ ____ __            (21) tag
 */

class InstCache extends Module {
  val io = IO(new Bundle {
    //val in = Flipped(new CacheBusIO)
    //val out = new SimpleAxiIO
  })

  val sram = Vec(4, Module(new Sram).io)

  val addr = 0.U
  val word_offs = addr(3, 2)
  val sram_idx = addr(5, 4)
  val set_idx = addr(9, 6)
  val tag = addr(30, 10)

  val s_init :: s_idle :: s_req :: s_wait :: Nil = Enum(4)
  val state = RegInit(s_init)

  
}
