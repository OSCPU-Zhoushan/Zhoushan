package zhoushan

import chisel3._
import chisel3.util._

/* InstCache configuration
 * 1xxx xxxx xxxx xxxx xxxx xxxx xxxx xxxx
 *                                     ___ (3) byte offset
 *                                    _    (1) dword offset (2 dwords in a line)
 *                                 __      (2) sram index (4 srams)
 *                            __ __        (4) set index (16 sets)
 *  ___ ____ ____ ____ ____ __            (21) tag
 */

class MetaData extends Bundle {
  val tags = RegInit(VecInit(Seq.fill(64)(0.U(21.W))))
  val valid = RegInit(VecInit(Seq.fill(64)(false.B)))
  // PLRU replacement policy
  // plru1    == 0 -> 0/1, == 1 -> 2/3
  // plru0(0) == 0 -> 0,   == 1 -> 1
  // plru0(1) == 0 -> 2,   == 1 -> 3
  val plru1 = RegInit(VecInit(Seq.fill(16)(0.U(1.W))))
  val plru0 = RegInit(VecInit(Seq.fill(16)(0.U(2.W))))
  def hit(set_idx: UInt, tag: UInt): (Bool, UInt) = {
    val idx = Vec(4, UInt(6.W))
    for (i <- 0 until 4) {
      idx(i) := Cat(set_idx, i.U(2.W))
    }
    val hit = Vec(4, Bool())
    for (i <- 0 until 4) {
      hit(i) := valid(idx(i)) && (tags(i) === tag)
    }
    (hit(0) || hit(1) || hit(2) || hit(3),
     Mux(hit(0), 0.U(2.W),
     Mux(hit(1), 1.U(2.W),
     Mux(hit(2), 2.U(2.W),
     Mux(hit(3), 3.U(3.W), 0.U(2.W)))))
    )
  }
}

class InstCache extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = new SimpleAxiIO
  })

  val sram = for (i <- 0 until 4) yield {
    val sram = Module(new Sram)
    sram
  }
  val meta = Vec(4, new MetaData)

  // set default input
  sram.map { case m => {
    m.io.en := false.B
    m.io.wen := false.B
    m.io.addr := 0.U
    m.io.wdata := 0.U
  }}

  val in = io.in
  val out = io.out

  val addr = in.req.bits.addr
  val dword_offs = addr(3)
  val sram_idx = addr(5, 4)
  val set_idx = addr(9, 6)
  val tag = addr(30, 10)

  val reg_addr = RegInit(UInt(32.W), 0.U)
  val reg_dword_offs = addr(3)
  val reg_sram_idx = addr(5, 4)
  val reg_set_idx = addr(9, 6)
  val reg_tag = addr(30, 10)

  // val hit = Bool()
  // val hit_idx = UInt(2.W)
  val (hit, hit_idx) = (false.B, 0.U)// meta(sram_idx).hit(set_idx, tag)

  val s_idle :: s_hit :: s_miss_req :: s_miss_wait :: s_miss_complete :: Nil = Enum(5)
  val state = RegInit(s_idle)
  val last_state = RegNext(state)

  in.req.ready := (state === s_idle)
  in.resp.valid := (state === s_hit) || (state === s_miss_complete)

  val rdata = WireInit(UInt(64.W), 0.U)
  for (i <- 0 until 4) {
    when (sram_idx === i.U) {
      rdata := Mux(dword_offs.asBool(), sram(i).io.rdata(127, 64), sram(i).io.rdata(63, 0))
    }
  }
  val reg_rdata = RegInit(UInt(64.W), 0.U)

  out.req.valid := (state === s_miss_req)
  out.req.bits.id := 1.U
  out.req.bits.addr := Cat(reg_addr(31, 4), Fill(4, 0.U))
  out.req.bits.ren := true.B
  out.req.bits.wdata := 0.U
  out.req.bits.wmask := 0.U
  out.req.bits.wlast := true.B
  out.req.bits.wen := false.B
  out.req.bits.len := 1.U
  out.resp.ready := (state === s_miss_wait)

  val wdata1 = RegInit(UInt(64.W), 0.U)
  val wdata2 = RegInit(UInt(64.W), 0.U)

  val plru1 = WireInit(UInt(4.W), 0.U)
  val plru0 = WireInit(UInt(4.W), 0.U)
  val line_idx = for (i <- 0 until 4) yield {
    val line_idx = WireInit(UInt(6.W), 0.U)
    line_idx
  }

  switch (state) {
    is (s_idle) {
      when (in.req.fire()) {
        when (hit) {
          for (i <- 0 until 4) {
            when (sram_idx === i.U) {
              sram(i).io.en := true.B
              sram(i).io.addr := Cat(set_idx, hit_idx)
            }
          }
          state := s_hit
        } .otherwise {
          reg_addr := addr
          state := s_miss_req
        }
      }
    }
    is (s_hit) {
      when (last_state === s_idle) {
        reg_rdata := rdata
      }
      when (in.resp.fire()) {
        in.resp.bits.rdata := Mux(last_state === s_idle, rdata, reg_rdata)
        state := s_idle
      }
    }
    is (s_miss_req) {
      when (out.req.fire()) {
        state := s_miss_wait
      }
    }
    is (s_miss_wait) {
      when (out.resp.fire()) {
        when (!out.resp.bits.rlast) {
          wdata1 := out.resp.bits.rdata
        } .otherwise {
          wdata2 := out.resp.bits.rdata
          state := s_miss_complete
        }
      }
    }
    is (s_miss_complete) {
      in.resp.bits.rdata := Mux(dword_offs.asBool(), wdata2, wdata1)
      for (i <- 0 until 4) {
        when (reg_sram_idx === i.U) {
          for (j <- 0 until 16) {
            when (reg_set_idx === j.U) {
              plru1(i) := meta(i).plru1(j)
              meta(i).plru1(j) := !plru1(i)
              when (plru1(i) === 0.U) {
                plru0(i) := meta(i).plru0(j)(0)
                meta(i).plru0(j)(0) := !plru0(i)
              } .otherwise {
                plru0(i) := meta(i).plru0(j)(1)
                meta(i).plru0(j)(1) := !plru0(i)
              }
            }
          }
          line_idx(i) := Cat(reg_set_idx, plru1(i), plru0(i))
          sram(i).io.en := true.B
          sram(i).io.wen := true.B
          sram(i).io.addr := line_idx(i)
          sram(i).io.wdata := Cat(wdata2, wdata1)
          for (j <- 0 until 64) {
            when (line_idx(i) === j.U) {
              meta(i).tags(j) := reg_tag
              meta(i).valid(j) := true.B
            }
          }
        }
      }
      state := s_idle
    }
  }

}
