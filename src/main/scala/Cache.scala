package zhoushan

import chisel3._
import chisel3.util._

/* Cache configuration
 * 1xxx xxxx xxxx xxxx xxxx xxxx xxxx xxxx
 *                                     ___ (3) byte offset
 *                                    _    (1) dword offset (2 dwords in a line)
 *                            __ ____      (6) set index (4-way associative in 4 srams)
 *  ___ ____ ____ ____ ____ __            (21) tag
 */

class Meta extends Module {
  val io = IO(new Bundle {
    val idx = Input(UInt(6.W))
    val tag_r = Output(UInt(21.W))
    val tag_w = Input(UInt(21.W))
    val tag_wen = Input(Bool())
    val dirty_r = Output(Bool())
    val dirty_w = Input(Bool())
    val dirty_wen = Input(Bool())
    val valid_r = Output(Bool())
  })

  val tags = SyncReadMem(64, UInt(21.W))
  val valid = RegInit(VecInit(Seq.fill(64)(false.B)))
  val dirty = RegInit(VecInit(Seq.fill(64)(false.B)))

  val idx = io.idx

  when (io.tag_wen) {
    tags.write(idx, io.tag_w)
    valid(idx) := true.B
  }
  io.tag_r := tags.read(idx)

  // sync read
  val dirty_r = RegNext(dirty(idx))
  io.dirty_r := dirty_r

  when (io.dirty_wen) {
    dirty(idx) := io.dirty_w
  }

  val valid_r = RegNext(valid(idx))
  io.valid_r := valid_r

}

class Cache(id: Int) extends Module with SramParameters {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = new CoreBusIO
  })

  val in = io.in
  val out = io.out

  val sram = for (i <- 0 until 4) yield {
    val sram = Module(new Sram(id * 10 + i))
    sram
  }

  val sram_out = WireInit(VecInit(Seq.fill(4)(0.U(SramDataWidth.W))))

  val meta = for (i <- 0 until 4) yield {
    val meta = Module(new Meta)
    meta
  }

  val tag_out = WireInit(VecInit(Seq.fill(4)(0.U(21.W))))
  val valid_out = WireInit(VecInit(Seq.fill(4)(false.B)))
  val dirty_out = WireInit(VecInit(Seq.fill(4)(false.B)))

  // set default input
  sram.map { case s => {
    s.io.en := false.B
    s.io.wen := false.B
    s.io.addr := 0.U
    s.io.wdata := 0.U
  }}
  meta.map { case m => {
    m.io.idx := 0.U
    m.io.tag_w := 0.U
    m.io.tag_wen := false.B
    m.io.dirty_w := false.B
    m.io.dirty_wen := false.B
  }}

  (sram_out  zip sram).map { case (o, s) => { o := s.io.rdata }}
  (tag_out   zip meta).map { case (t, m) => { t := m.io.tag_r }}
  (valid_out zip meta).map { case (v, m) => { v := m.io.valid_r }}
  (dirty_out zip meta).map { case (d, m) => { d := m.io.dirty_r }}

  /* ----- PLRU replacement ---------- */

  val plru0 = RegInit(VecInit(Seq.fill(64)(0.U)))
  val plru1 = RegInit(VecInit(Seq.fill(64)(0.U)))
  val plru2 = RegInit(VecInit(Seq.fill(64)(0.U)))

  def updatePlruTree(idx: UInt, way: UInt) = {
    plru0(idx) := ~way(1)
    when (way(1) === 0.U) {
      plru1(idx) := ~way(0)
    } .otherwise {
      plru2(idx) := ~way(0)
    }
  }

  val replace_way = Wire(UInt(2.W))

  /* ----- Pipeline Ctrl Signals ----- */

  val s1_valid = WireInit(false.B)
  val s2_ready = WireInit(false.B)
  val s1_fire  = s1_valid && s2_ready
  val s2_valid = WireInit(false.B)
  val s3_ready = WireInit(false.B)
  val s2_fire  = s2_valid && s3_ready

  /* ----- Cache Stage 1 ------------- */

  val s1_addr  = in.req.bits.addr
  val s1_offs  = s1_addr(3)
  val s1_idx   = s1_addr(9, 4)
  val s1_tag   = s1_addr(30, 10)
  val s1_wen   = in.req.bits.wen
  val s1_wdata = in.req.bits.wdata
  val s1_wmask = in.req.bits.wmask

  when (s1_fire) {
    sram.map { case s => {
      s.io.en := true.B
      s.io.addr := s1_idx
    }}
    meta.map { case m => {
      m.io.idx := s1_idx
    }}
  }

  /* ----- Cache Stage 2 ------------- */

  val s2_addr  = RegInit(0.U(32.W))
  val s2_offs  = s2_addr(3)
  val s2_idx   = s2_addr(9, 4)
  val s2_tag   = s2_addr(30, 10)
  val s2_wen   = RegInit(false.B)
  val s2_wdata = RegInit(0.U(64.W))
  val s2_wmask = RegInit(0.U(8.W))

  when (s1_fire) {
    s2_addr  := s1_addr
    s2_wen   := s1_wen
    s2_wdata := s1_wdata
    s2_wmask := s1_wmask
  }

  val hit = WireInit(VecInit(Seq.fill(4)(false.B)))
  (hit zip (tag_out zip valid_out)).map { case (h, (t, v)) => {
    h := (t === s2_tag) && v
  }}
  val s2_hit = hit(0) || hit(1) || hit(2) || hit(3)  // todo
  val s2_way = OHToUInt(hit)

  val s2_rdata = sram_out(s2_way)
  val s2_dirty = dirty_out(s2_way)

  val s2_tag_r = tag_out(replace_way)

  /* ----- Cache Stage 3 ------------- */

  val s3_addr  = RegInit(0.U(32.W))
  val s3_offs  = s3_addr(3)
  val s3_idx   = s3_addr(9, 4)
  val s3_tag   = s3_addr(30, 10)
  val s3_hit   = RegInit(false.B)
  val s3_way   = RegInit(0.U(2.W))
  val s3_rdata = RegInit(0.U(128.W))
  val s3_wen   = RegInit(false.B)
  val s3_wdata = RegInit(0.U(64.W))
  val s3_wmask = RegInit(0.U(8.W))
  val s3_dirty = RegInit(false.B)
  val s3_tag_r = RegInit(0.U(21.W))

  val s_idle :: s_hit_r :: s_hit_w :: s_miss_req_r :: s_miss_wait_r :: s1 = Enum(9)
  val s_miss_ok_r :: s_miss_req_w1 :: s_miss_req_w2 :: s_miss_wait_w :: Nil = s1
  val state = RegInit(s_idle)
  val state_init = WireInit(s_idle)

  when (s2_hit) {
    state_init := Mux(s2_wen, s_hit_w, s_hit_r)
  } .otherwise {
    state_init := s_miss_req_r
  }

  when (s2_fire) {
    s3_addr  := s2_addr
    s3_hit   := s2_hit
    s3_way   := s2_way
    s3_rdata := s2_rdata
    s3_wen   := s2_wen
    s3_wdata := s2_wdata
    s3_wmask := s2_wmask
    s3_dirty := s2_dirty
    s3_tag_r := s2_tag_r
  }

  replace_way := Cat(plru0(s3_idx), Mux(plru0(s3_idx) === 0.U, plru1(s3_idx), plru2(s3_idx)))
  val wb_addr = Cat(1.U, s3_tag_r, s3_idx, Fill(4, 0.U))
  val wdata1 = RegInit(UInt(64.W), 0.U)
  val wdata2 = RegInit(UInt(64.W), 0.U)

  /* ----- Pipeline Ctrl Signals ----- */

  s1_valid := in.req.fire()
  s2_ready := s3_ready
  s2_valid := RegNext(s1_valid)
  s3_ready := (state === s_idle) || (state === s_hit_r && in.resp.fire())

  // handshake signals with IF unit
  in.req.ready := s2_ready
  in.resp.valid := (state === s_hit_r) || (state === s_hit_w) || (state === s_miss_ok_r)
  in.resp.bits.rdata := 0.U

  // handshake signals with memory

  out.req.valid := (state === s_miss_req_r) || (state === s_miss_req_w1) || (state === s_miss_req_w2)
  out.req.bits.id := id.U
  out.req.bits.addr := Mux(state === s_miss_req_r, Cat(s3_addr(31, 4), Fill(4, 0.U)), Cat(wb_addr(31, 4), Fill(4, 0.U)))
  out.req.bits.aen := (state === s_miss_req_r) || (state === s_miss_req_w1)
  out.req.bits.ren := (state === s_miss_req_r)
  out.req.bits.wdata := Mux(state === s_miss_req_w1, s3_rdata(63, 0), s3_rdata(127, 64))
  out.req.bits.wmask := "hff".U(8.W)
  out.req.bits.wlast := (state === s_miss_req_w2)
  out.req.bits.wen := (state === s_miss_req_w1) || (state === s_miss_req_w2)
  out.req.bits.len := 1.U
  out.resp.ready := (state === s_miss_wait_r) || (state === s_miss_wait_w)

  // state machine

  switch (state) {
    is (s_hit_r) {
      in.resp.bits.rdata := Mux(s3_offs === 1.U, s3_rdata(127, 64), s3_rdata(63, 0))
      when (in.resp.fire()) {
        updatePlruTree(s3_idx, s3_way)
        state := s_idle
      }
    }
    is (s_hit_w) {
      when (in.resp.fire()) {
        for (i <- 0 until 4) {
          when (s3_way === i.U) {
            sram(i).io.en := true.B
            sram(i).io.wen := true.B
            sram(i).io.addr := s3_idx
            sram(i).io.wdata := Mux(s3_offs === 1.U, 
                                    Cat(MaskData(s3_rdata(127, 64), s3_wdata, MaskExpand(s3_wmask)), s3_rdata(63, 0)),
                                    Cat(s3_rdata(127, 64), MaskData(s3_rdata(63, 0), s3_wdata, MaskExpand(s3_wmask))))
            meta(i).io.idx := s3_idx
            meta(i).io.dirty_wen := true.B
            meta(i).io.dirty_w := true.B
          }
        }
        updatePlruTree(s3_idx, s3_way)
      }
      state := s_idle
    }
    is (s_miss_req_r) {
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
      in.resp.bits.rdata := Mux(s3_wen, 0.U, Mux(s3_offs.asBool(), wdata2, wdata1))
      when (in.resp.fire()) {
        for (i <- 0 until 4) {
          when (replace_way === i.U) {
            sram(i).io.en := true.B
            sram(i).io.wen := true.B
            sram(i).io.addr := s3_idx
            when (s3_wen) {
              sram(i).io.wdata := Mux(s3_offs === 1.U, 
                                      Cat(MaskData(wdata2, s3_wdata, MaskExpand(s3_wmask)), wdata1),
                                      Cat(wdata2, MaskData(wdata1, s3_wdata, MaskExpand(s3_wmask))))
            } .otherwise {
              sram(i).io.wdata := Cat(wdata2, wdata1)
            }
            // write allocate
            meta(i).io.idx := s3_idx
            meta(i).io.tag_wen := true.B
            meta(i).io.tag_w := s3_tag
            meta(i).io.dirty_wen := true.B
            meta(i).io.dirty_w := s3_wen
          }
        }
        state := Mux(s3_dirty, s_miss_req_w1, s_idle)
      }
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

  when (s2_fire) {
    state := state_init
  }

}
