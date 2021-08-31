package zhoushan

import chisel3._
import chisel3.util._

/* Cache configuration
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
    // mark dirty
    val dirty_en = Input(Bool())
    val dirty_set_idx = Input(UInt(4.W))
    val dirty_entry_idx = Input(UInt(2.W))
    // update plru
    val plru_update = Input(Bool())
    val plru_set_idx = Input(UInt(4.W))
    val plru_entry_idx = Input(UInt(2.W))
    // replace (and also update plru)
    val replace_en = Input(Bool())
    val replace_set_idx = Input(UInt(4.W))
    val replace_tag = Input(UInt(21.W))
    val replace_entry_idx = Output(UInt(2.W))
    val replace_en_dirty = Input(Bool())
    // write-back
    val wb_en = Output(Bool())
    val wb_tag = Output(UInt(21.W))
  })

  val tags = RegInit(VecInit(Seq.fill(64)(0.U(21.W))))
  val valid = RegInit(VecInit(Seq.fill(64)(false.B)))
  val dirty = RegInit(VecInit(Seq.fill(64)(false.B)))
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
    check_hit(i) := valid(check_line_idx(i)) && (tags(check_line_idx(i)) === io.check_tag)
  }

  io.check_hit := check_hit(0) || check_hit(1) || check_hit(2) || check_hit(3)
  io.check_hit_idx := Mux1H(Seq(
    check_hit(0) -> 0.U(2.W),
    check_hit(1) -> 1.U(2.W),
    check_hit(2) -> 2.U(2.W),
    check_hit(3) -> 3.U(3.W)
  ))

  // mark dirty

  val dirty_line_idx = Cat(io.dirty_set_idx, io.dirty_entry_idx)

  when (io.dirty_en) {
    dirty(dirty_line_idx) := true.B
  }

  // update plru, or replace (and also update plru)

  val replace_plru0 = plru(io.replace_set_idx)(0)
  val replace_plru1 = Mux(replace_plru0, plru(io.replace_set_idx)(1),
                                         plru(io.replace_set_idx)(2))
  val replace_entry_idx = Cat(replace_plru0, replace_plru1)
  val replace_line_idx = Cat(io.replace_set_idx, replace_entry_idx)

  val plru0, plru1, plru2 = WireInit(false.B)
  val plru_new = Cat(plru2.asUInt(), plru1.asUInt(), plru0.asUInt())

  when (io.plru_update) {
    // update plru tree when hit
    plru0 := !io.plru_entry_idx(1)
    when (io.plru_entry_idx(0) === 0.U) {
      plru1 := !io.plru_entry_idx(0)
    } .otherwise {
      plru2 := !io.plru_entry_idx(0)
    }
    plru(io.plru_set_idx) := plru_new
  } .elsewhen (io.replace_en) {
    // update plru tree when miss/replace
    plru0 := !replace_entry_idx(1)
    when (replace_plru0 === 0.U) {
      plru1 := !replace_entry_idx(0)
    } .otherwise {
      plru2 := !replace_entry_idx(0)
    }
    plru(io.replace_set_idx) := plru_new
    tags(replace_line_idx) := io.replace_tag
    valid(replace_line_idx) := true.B
    dirty(replace_line_idx) := io.replace_en_dirty
  }
  io.replace_entry_idx := replace_entry_idx
  io.wb_en := dirty(replace_line_idx) && valid(replace_line_idx)
  io.wb_tag := tags(replace_line_idx)
}

class Cache(id: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = new CoreBusIO
  })

  val sram = for (i <- 0 until 4) yield {
    val sram = Module(new Sram(id * 10 + i))
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
    m.io.dirty_en := false.B
    m.io.dirty_set_idx := 0.U
    m.io.dirty_entry_idx := 0.U
    m.io.plru_update := false.B
    m.io.plru_set_idx := 0.U
    m.io.plru_entry_idx := 0.U
    m.io.replace_en := false.B
    m.io.replace_set_idx := 0.U
    m.io.replace_tag := 0.U
    m.io.replace_en_dirty := false.B
  }}

  val in = io.in
  val out = io.out

  val addr = in.req.bits.addr
  val dword_offs = addr(3)
  val sram_idx = addr(5, 4)
  val set_idx = addr(9, 6)
  val tag = addr(30, 10)

  val reg_addr = RegInit(UInt(32.W), 0.U)
  val reg_dword_offs = reg_addr(3)
  val reg_sram_idx = reg_addr(5, 4)
  val reg_set_idx = reg_addr(9, 6)
  val reg_tag = reg_addr(30, 10)

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

  val rdata_raw = WireInit(UInt(128.W), 0.U)
  for (i <- 0 until 4) {
    when (sram_idx === i.U) {
      rdata_raw := sram(i).io.rdata
    }
  }
  val rdata = Mux(reg_dword_offs.asBool(), rdata_raw(127, 64), rdata_raw(63, 0))
  val reg_rdata_raw = RegInit(UInt(128.W), 0.U)
  val reg_rdata = Mux(reg_dword_offs.asBool(), reg_rdata_raw(127, 64), reg_rdata_raw(63, 0))

  val wb_en = WireInit(false.B)
  val wb_addr = WireInit(UInt(32.W), 0.U)
  for (i <- 0 until 4) {
    when (sram_idx === i.U) {
      wb_en := meta(i).io.wb_en
      wb_addr := Cat(1.U(1.W), meta(i).io.wb_tag, addr(9, 0))
    }
  }
  val reg_wb_en = RegInit(false.B)
  val reg_wb_addr = RegInit(UInt(32.W), 0.U)
  val reg_wb_tag = reg_wb_addr(30, 10)

  val s_idle :: s_hit_r :: s_hit_w :: s_miss_req_r :: s_miss_wait_r :: s1 = Enum(9)
  val s_miss_ok_r :: s_miss_req_w1 :: s_miss_req_w2 :: s_miss_wait_w :: Nil = s1
  val state = RegInit(s_idle)
  val last_state = RegNext(state)

  in.req.ready := (state === s_idle)
  in.resp.valid := (state === s_hit_r) || (state === s_hit_w) || (state === s_miss_ok_r)
  in.resp.bits.rdata := 0.U

  out.req.valid := (state === s_miss_req_r) || (state === s_miss_req_w1) || (state === s_miss_req_w2)
  out.req.bits.id := id.U
  out.req.bits.addr := Mux(state === s_miss_req_r, Cat(reg_addr(31, 4), Fill(4, 0.U)), Cat(reg_wb_addr(31, 4), Fill(4, 0.U)))
  out.req.bits.aen := (state === s_miss_req_r) || (state === s_miss_req_w1)
  out.req.bits.ren := (state === s_miss_req_r)
  out.req.bits.wdata := Mux(state === s_miss_req_w1, reg_rdata_raw(63, 0), reg_rdata_raw(127, 64))
  out.req.bits.wmask := "hff".U(8.W)
  out.req.bits.wlast := (state === s_miss_req_w2)
  out.req.bits.wen := (state === s_miss_req_w1) || (state === s_miss_req_w2)
  out.req.bits.len := 1.U
  out.resp.ready := (state === s_miss_wait_r) || (state === s_miss_wait_w)

  val wdata1 = RegInit(UInt(64.W), 0.U)
  val wdata2 = RegInit(UInt(64.W), 0.U)

  switch (state) {
    is (s_idle) {
      reg_addr := addr
      when (in.req.fire()) {
        when (hit) {
          for (i <- 0 until 4) {
            when (sram_idx === i.U) {
              sram(i).io.en := true.B
              sram(i).io.addr := Cat(set_idx, hit_idx)
            }
          }
          reg_hit_idx := hit_idx
          state := Mux(in.req.bits.wen, s_hit_w, s_hit_r)
        } .otherwise {
          state := s_miss_req_r
          // before writing back, get the data in current cacheline
          for (i <- 0 until 4) {
            when (sram_idx === i.U) {
              meta(i).io.replace_set_idx := set_idx
              sram(i).io.en := true.B
              sram(i).io.addr := Cat(set_idx, meta(i).io.replace_entry_idx)
            }
          }
          reg_wb_en := wb_en
          reg_wb_addr := wb_addr
        }
      }
    }
    is (s_hit_r) {
      when (last_state === s_idle) {
        reg_rdata_raw := rdata_raw
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
    is (s_hit_w) {
      when (last_state === s_idle) {
        for (i <- 0 until 4) {
          when (reg_sram_idx === i.U) {
            sram(i).io.en := true.B
            sram(i).io.wen := true.B
            sram(i).io.addr := Cat(reg_set_idx, reg_hit_idx)
            sram(i).io.wdata := Mux(reg_dword_offs.asBool(),
              Cat(MaskData(rdata_raw(127, 64), in.req.bits.wdata, MaskExpand(in.req.bits.wmask)), rdata_raw(63, 0)),
              Cat(rdata_raw(127, 64), MaskData(rdata_raw(63, 0), in.req.bits.wdata, MaskExpand(in.req.bits.wmask)))
            )
            MaskData(rdata, in.req.bits.wdata, MaskExpand(in.req.bits.wmask))
            // mark as dirty
            meta(i).io.dirty_en := true.B
            meta(i).io.dirty_set_idx := reg_set_idx
            meta(i).io.dirty_entry_idx := reg_hit_idx
          }
        }
      }
      when (in.resp.fire()) {
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
    is (s_miss_req_r) {
      when (last_state === s_idle) {
        reg_rdata_raw := rdata_raw
      }
      when (out.req.fire()) {
        state := s_miss_wait_r
      }
    }
    is (s_miss_wait_r) {
      when (out.resp.fire()) {
        when (!out.resp.bits.rlast) {
          wdata1 := out.resp.bits.rdata
        } .otherwise {
          wdata2 := out.resp.bits.rdata
          state := s_miss_ok_r
        }
      }
    }
    is (s_miss_ok_r) {
      in.resp.bits.rdata := Mux(in.req.bits.wen, 0.U, Mux(dword_offs.asBool(), wdata2, wdata1))
      for (i <- 0 until 4) {
        when (reg_sram_idx === i.U) {
          sram(i).io.en := true.B
          sram(i).io.wen := true.B
          sram(i).io.addr := Cat(reg_set_idx, meta(i).io.replace_entry_idx)
          when (in.req.bits.wen) {
            sram(i).io.wdata := Mux(reg_dword_offs.asBool(),
              Cat(MaskData(wdata2, in.req.bits.wdata, MaskExpand(in.req.bits.wmask)), wdata1),
              Cat(wdata2, MaskData(wdata1, in.req.bits.wdata, MaskExpand(in.req.bits.wmask)))
            )
          } .otherwise {
            sram(i).io.wdata := Cat(wdata2, wdata1)
          }
          // write allocate
          meta(i).io.replace_en := true.B
          meta(i).io.replace_set_idx := reg_set_idx
          meta(i).io.replace_tag := reg_tag
          meta(i).io.replace_en_dirty := true.B
        }
      }
      state := Mux(reg_wb_en, s_miss_req_w1, s_idle)
    }
    is (s_miss_req_w1) {
      when (out.req.fire()) {
        state := s_miss_req_w2
      }
    }
    is (s_miss_req_w2) {
      when (out.req.fire()) {
        state := s_miss_wait_w
      }
    }
    is (s_miss_wait_w) {
      when (out.resp.fire()) {
        state := s_idle
      }
    }
  }
}
