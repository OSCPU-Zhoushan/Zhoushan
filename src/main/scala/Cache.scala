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

class MetaData extends Module {
  val io = IO(new Bundle {
    // check hit or not
    val check_set_idx = Input(UInt(4.W))
    val check_tag = Input(UInt(21.W))
    val check_hit = Output(Bool())
    val check_hit_idx = Output(UInt(2.W))
    // update plru
    val plru_update = Input(Bool())
    val plru_set_idx = Input(UInt(4.W))
    val plru_entry_idx = Input(UInt(2.W))
    // replace (and also update plru)
    val replace_en = Input(Bool())
    val replace_set_idx = Input(UInt(4.W))
    val replace_tag = Input(UInt(21.W))
    val replace_entry_idx = Output(UInt(2.W))
  })

  val tags = RegInit(VecInit(Seq.fill(64)(0.U(21.W))))
  val valid = RegInit(VecInit(Seq.fill(64)(false.B)))
  // Tree-based PLRU replacement policy
  // plru(0) == 0 -> 0/1, == 1 -> 2/3
  // plru(1) == 0 -> 0,   == 1 -> 1
  // plru(2) == 0 -> 2,   == 1 -> 3
  val plru = RegInit(VecInit(Seq.fill(16)(0.U(3.W))))

  // check hit or not

  val check_line_idx = Wire(Vec(4, UInt(6.W)))
  for (i <- 0 until 4) {
    check_line_idx(i) := Cat(io.check_set_idx, i.U(2.W))
  }
  val check_hit = Wire(Vec(4, Bool()))
  for (i <- 0 until 4) {
    check_hit(i) := valid(check_line_idx(i)) && (tags(i) === io.check_tag)
  }

  io.check_hit := check_hit(0) || check_hit(1) || check_hit(2) || check_hit(3)
  io.check_hit_idx := Mux1H(Seq(
    check_hit(0) -> 0.U(2.W),
    check_hit(1) -> 1.U(2.W),
    check_hit(2) -> 2.U(2.W),
    check_hit(3) -> 3.U(3.W)
  ))

  // update plru, or replace (and also update plru)
  // ref: http://blog.sina.com.cn/s/blog_65a6c8eb0101ez8w.html

  val replace_plru0 = plru(io.replace_set_idx)(0)
  val replace_plru1 = Mux(replace_plru0, plru(io.replace_set_idx)(1),
                                         plru(io.replace_set_idx)(2))
  val replace_entry_idx = Cat(replace_plru0, replace_plru1)
  val replace_line_idx = Cat(io.replace_set_idx, replace_entry_idx)

  val plru0, plru1, plru2 = WireInit(false.B)
  val plru_new = Cat(plru2.asUInt(), plru1.asUInt(), plru0.asUInt())

  when (io.plru_update) {
    plru0 := !io.plru_entry_idx(1)
    when (io.plru_entry_idx(0) === 0.U) {
      plru1 := !io.plru_entry_idx(0)
    } .otherwise {
      plru2 := !io.plru_entry_idx(0)
    }
    plru(io.plru_set_idx) := plru_new
  } .elsewhen (io.replace_en) {
    plru0 := !replace_entry_idx(1)
    when (replace_plru0 === 0.U) {
      plru1 := !replace_entry_idx(0)
    } .otherwise {
      plru2 := !replace_entry_idx(0)
    }
    plru(io.replace_set_idx) := plru_new
    tags(replace_line_idx) := io.replace_tag
    valid(replace_line_idx) := true.B
  }
  io.replace_entry_idx := replace_entry_idx
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
  val meta = for (i <- 0 until 4) yield {
    val meta = Module(new MetaData)
    meta
  }

  // set default input
  sram.map { case m => {
    m.io.en := false.B
    m.io.wen := false.B
    m.io.addr := 0.U
    m.io.wdata := 0.U
  }}
  meta.map { case m => {
    m.io.check_set_idx := 0.U
    m.io.check_tag := 0.U
    m.io.plru_update := false.B
    m.io.plru_set_idx := 0.U
    m.io.plru_entry_idx := 0.U
    m.io.replace_en := false.B
    m.io.replace_set_idx := 0.U
    m.io.replace_tag := 0.U
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

  val hit = WireInit(false.B)
  val hit_idx = WireInit(0.U(2.W))
  for (i <- 0 until 4) {
    when (sram_idx === i.U) {
      meta(i).io.check_set_idx := set_idx
      meta(i).io.check_tag := tag
      hit := meta(i).io.check_hit
      hit_idx := meta(i).io.check_hit_idx
    }
  }
  val reg_hit_idx = RegInit(0.U(2.W))

  val s_idle :: s_hit :: s_miss_req :: s_miss_wait :: s_miss_complete :: Nil = Enum(5)
  val state = RegInit(s_idle)
  val last_state = RegNext(state)

  val rdata = WireInit(UInt(64.W), 0.U)
  for (i <- 0 until 4) {
    when (sram_idx === i.U) {
      rdata := Mux(dword_offs.asBool(), sram(i).io.rdata(127, 64), 
                                        sram(i).io.rdata(63, 0))
    }
  }
  val reg_rdata = RegInit(UInt(64.W), 0.U)

  in.req.ready := (state === s_idle)
  in.resp.valid := (state === s_hit) || (state === s_miss_complete)
  in.resp.bits.rdata := 0.U

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
          reg_hit_idx := hit_idx
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
        // update plru
        for (i <- 0 until 4) {
          when (reg_sram_idx === i.U) {
            meta(i).io.plru_update := true.B
            meta(i).io.plru_set_idx := reg_set_idx
            meta(i).io.plru_entry_idx := reg_hit_idx
          }
        }
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
          sram(i).io.en := true.B
          sram(i).io.wen := true.B
          sram(i).io.addr := Cat(reg_set_idx, meta(i).io.replace_entry_idx)
          sram(i).io.wdata := Cat(wdata2, wdata1)
          meta(i).io.replace_en := true.B
          meta(i).io.replace_set_idx := reg_set_idx
          meta(i).io.replace_tag := reg_tag
        }
      }
      state := s_idle
    }
  }

}
