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
  val replace_idx = RegInit(VecInit(Seq.fill(16)(0.U(2.W))))
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

  val sram = Vec(4, Module(new Sram).io)
  val meta = Vec(4, new MetaData)

  // set default input
  sram.map { case m => {
    m.en := false.B
    m.wen := false.B
    m.addr := 0.U
    m.wdata := 0.U
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
  val (hit, hit_idx) = meta(sram_idx).hit(set_idx, tag)

  val s_idle :: s_hit :: s_miss_req :: s_miss_wait :: s_miss_replace :: Nil = Enum(5)
  val state = RegInit(s_idle)
  val last_state = RegNext(state)

  in.req.ready := (state === s_idle)
  in.resp.valid := (state === s_hit)

  val rdata = Mux(dword_offs.asBool(), sram(sram_idx).rdata(127, 64), sram(sram_idx).rdata(63, 0))
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

  val wdata = RegInit(UInt(128.W), 0.U)

  switch (state) {
    is (s_idle) {
      when (in.req.fire()) {
        when (hit) {
          sram(sram_idx).en := true.B
          sram(sram_idx).addr := Cat(set_idx, hit_idx)
          state := s_hit
        } .otherwise {
          reg_addr := addr
          reg_dword_offs := dword_offs
          reg_sram_idx := sram_idx
          reg_set_idx := set_idx
          reg_tag := tag
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

      }
    }
    is (s_miss_wait) {
      
    }
    is (s_miss_replace) {

    }
  }
  
}
