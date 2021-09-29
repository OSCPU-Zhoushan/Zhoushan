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

  // tag = addr(30, 10)
  // addr(31) is skipped in that
  //   1) only data in memory rather than MMIO could be stored in cache
  //   2) address map: memory - 0x8000_0000 ~ 0xffff_ffff
  val tags = SyncReadMem(64, UInt(21.W))

  val valid = RegInit(VecInit(Seq.fill(64)(false.B)))

  // dirty bit marks whether any bytes in the cacheline is written, but not
  // written back to memory yet, only used in D$
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

// 2-stage pipeline 4KB cache
class Cache(id: Int) extends Module with SramParameters with ZhoushanConfig {
  val io = IO(new Bundle {
    val in = Flipped(new CacheBusIO)
    val out = new CoreBusIO
  })

  val in = io.in
  val out = io.out

  // 4 interleaving sram, each of which corresponds to a single cacheline of
  // the 4-way associative cache
  val sram = for (i <- 0 until 4) yield {
    val sram = Module(new Sram(id * 10 + i))
    sram
  }

  val sram_out = WireInit(VecInit(Seq.fill(4)(0.U(SramDataWidth.W))))

  // 4 interleaving meta data array
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

  // plru0 == 0 --> way 0/1, == 1 --> way 2/3
  val plru0 = RegInit(VecInit(Seq.fill(64)(0.U)))
  // plru1 == 0 --> way 0,   == 1 --> way 1
  val plru1 = RegInit(VecInit(Seq.fill(64)(0.U)))
  // plru2 == 0 --> way 2,   == 1 --> way 3
  val plru2 = RegInit(VecInit(Seq.fill(64)(0.U)))

  def updatePlruTree(idx: UInt, way: UInt) = {
    plru0(idx) := ~way(1)
    when (way(1) === 0.U) {
      plru1(idx) := ~way(0)
    } .otherwise {
      plru2(idx) := ~way(0)
    }
  }

  // when a miss occurs, decide the victim cacheline in the current 4-way set
  val replace_way = Wire(UInt(2.W))

  /* ----- Pipeline Ctrl Signals ----- */

  val pipeline_valid = WireInit(false.B)
  val pipeline_ready = WireInit(false.B)
  val pipeline_fire  = pipeline_valid && pipeline_ready

  /* ----- Cache Stage 1 ------------- */

  val s1_addr  = in.req.bits.addr
  val s1_offs  = s1_addr(3)
  val s1_idx   = s1_addr(9, 4)
  val s1_tag   = s1_addr(30, 10)
  val s1_wen   = in.req.bits.wen
  val s1_wdata = in.req.bits.wdata
  val s1_wmask = in.req.bits.wmask
  val s1_user  = in.req.bits.user
  val s1_id    = in.req.bits.id
  val s1_valid = in.req.valid

  // when pipeline fire, read the data in SRAM and meta data array
  // the data will be returned at the next clock cycle, and passed to stage 2

  when (pipeline_fire) {
    sram.map { case s => {
      s.io.en := true.B
      s.io.addr := s1_idx
    }}
    meta.map { case m => {
      m.io.idx := s1_idx
    }}
  }

  /* ----- Cache Stage 2 ------------- */

  /* 9-state FSM in cache stage 2
   *
   * read  hit:  idle
   * write hit:  idle -> complete
   * read  miss: idle -> miss_req_r -> miss_wait_r -> miss_ok_r -> complete
   * write miss: idle -> miss_req_r -> miss_wait_r -> miss_ok_r -> ...
   *             (not dirty) ... -> complete
   *             (dirty)     ... -> miss_req_w1 -> miss_req_w2 -> miss_wait_w
   *                         -> complete
   */

  val s_idle        :: s_miss_req_r  :: s_miss_wait_r :: s1  = Enum(9)
  val s_miss_ok_r   :: s_miss_req_w1 :: s_miss_req_w2 :: s2  = s1
  val s_miss_wait_w :: s_complete    :: s_invalid     :: Nil = s2
  val state = RegInit(s_invalid)

  val s2_addr  = RegInit(0.U(32.W))
  val s2_offs  = s2_addr(3)
  val s2_idx   = s2_addr(9, 4)
  val s2_tag   = s2_addr(30, 10)
  val s2_wen   = RegInit(false.B)
  val s2_wdata = RegInit(0.U(64.W))
  val s2_wmask = RegInit(0.U(8.W))
  val s2_user  = RegInit(0.U(s1_user.getWidth.W))
  val s2_id    = RegInit(0.U(s1_id.getWidth.W))

  // 4-bit hit check vector, with one-hot encoding
  // Example 1: hit on line 0, hit = (0, 0, 0, 1)
  // Example 2: miss,          hit = (0, 0, 0, 0)
  val hit = WireInit(VecInit(Seq.fill(4)(false.B)))
  (hit zip (tag_out zip valid_out)).map { case (h, (t, v)) => {
    h := (t === s2_tag) && v
  }}
  val s2_hit = Cat(hit).orR
  val s2_way = OHToUInt(hit)
  val s2_rdata = sram_out(s2_way)
  val s2_dirty = dirty_out(replace_way)
  val s2_tag_r = tag_out(replace_way)
  val s2_dat_w = sram_out(replace_way)

  val s2_reg_hit = RegInit(false.B)
  val s2_reg_way = RegInit(0.U(2.W))
  val s2_reg_rdata = RegInit(0.U(128.W))
  val s2_reg_dirty = RegInit(false.B)
  val s2_reg_tag_r = RegInit(0.U(21.W))
  val s2_reg_dat_w = RegInit(0.U(128.W))

  when (pipeline_fire) {
    // when pipeline fire, pass the data from stage 1 to stage 2
    s2_addr  := s1_addr
    s2_wen   := s1_wen
    s2_wdata := s1_wdata
    s2_wmask := s1_wmask
    s2_user  := s1_user
    s2_id    := s1_id
  } .elsewhen (!pipeline_fire && RegNext(pipeline_fire)) {
    // meanwhile, when the FSM is triggered in stage 2, we need to temporarily
    // store the data in registers
    s2_reg_hit   := s2_hit
    s2_reg_way   := s2_way
    s2_reg_rdata := s2_rdata
    s2_reg_dirty := s2_dirty
    s2_reg_tag_r := s2_tag_r
    s2_reg_dat_w := s2_dat_w
  }

  replace_way := Cat(plru0(s2_idx),
                     Mux(plru0(s2_idx) === 0.U, plru1(s2_idx), plru2(s2_idx)))

  // generate the write-back address by concatenating the tag stored in meta
  // data array and s2_idx
  val wb_addr = Cat(1.U, s2_reg_tag_r, s2_idx, Fill(4, 0.U))
  val wdata1 = RegInit(UInt(64.W), 0.U)           // cacheline(63, 0)
  val wdata2 = RegInit(UInt(64.W), 0.U)           // cacheline(127, 64)

  /* ----- Pipeline Ctrl Signals ----- */

  val s2_hit_real = Mux(RegNext(pipeline_fire), s2_hit, s2_reg_hit)
  val s2_dirty_real = Mux(RegNext(pipeline_fire), s2_dirty, s2_reg_dirty)

  val hit_ready = s2_hit_real &&
                  Mux(s2_wen, state === s_complete, state === s_idle)
  val miss_ready = (state === s_complete)
  val invalid_ready = (state === s_invalid)

  pipeline_valid := true.B
  pipeline_ready := ((hit_ready || miss_ready) && in.resp.ready) || invalid_ready

  // handshake signals with IF unit
  in.req.ready := pipeline_ready
  in.resp.valid := (s2_hit_real && !s2_wen && (state =/= s_invalid)) || (state === s_complete)
  in.resp.bits.rdata := 0.U
  in.resp.bits.user := s2_user
  in.resp.bits.id := s2_id

  // handshake signals with memory
  out.req.valid := (state === s_miss_req_r) || (state === s_miss_req_w1) || (state === s_miss_req_w2)
  out.req.bits.id := id.U
  out.req.bits.addr := Mux(state === s_miss_req_r,
                           Cat(s2_addr(31, 4), Fill(4, 0.U)),
                           Cat(wb_addr(31, 4), Fill(4, 0.U)))
  out.req.bits.aen := (state === s_miss_req_r) || (state === s_miss_req_w1)
  out.req.bits.ren := (state === s_miss_req_r)
  out.req.bits.wdata := Mux(state === s_miss_req_w1,
                            s2_reg_dat_w(63, 0),
                            s2_reg_dat_w(127, 64))
  out.req.bits.wmask := "hff".U(8.W)
  out.req.bits.wlast := (state === s_miss_req_w2)
  out.req.bits.wen := (state === s_miss_req_w1) || (state === s_miss_req_w2)
  out.req.bits.len := 1.U
  out.req.bits.size := 1.U
  out.resp.ready := (state === s_miss_wait_r) || (state === s_miss_wait_w)

  if ((DebugICache && id == 1) || (DebugDCache && id == 2)) {
    when (in.req.fire()) {
      printf("%d: [$ %d] [IN -REQ ] addr=%x wen=%x wdata=%x\n", DebugTimer(), id.U, in.req.bits.addr, in.req.bits.wen, in.req.bits.wdata)
    }
    when (in.resp.fire()) {
      printf("%d: [$ %d] [IN -RESP] addr=%x wen=%x rdata=%x\n", DebugTimer(), id.U, s2_addr, s2_wen, in.resp.bits.rdata)
    }
    when (RegNext(pipeline_fire)) {
      printf("%d: [$ %d] hit=%x idx=%d way=%d rdata=%x dirty=%x replace_way=%d tag_r=%x dat_w=%x\n", DebugTimer(), id.U, s2_hit, s2_idx, s2_way, s2_rdata, s2_dirty, replace_way, s2_tag_r, s2_dat_w)
    }
    when (out.req.fire()) {
      printf("%d: [$ %d] [OUT-REQ ] addr=%x aen=%x wen=%x wdata=%x\n", DebugTimer(), id.U, out.req.bits.addr, out.req.bits.aen, out.req.bits.wen, out.req.bits.wdata)
    }
    when (out.resp.fire()) {
      printf("%d: [$ %d] [OUT-RESP] rdata=%x\n", DebugTimer(), id.U, out.resp.bits.rdata)
    }
  }

  // state machine

  switch (state) {
    is (s_idle) {
      when (RegNext(pipeline_fire)) {
        in.resp.bits.rdata := Mux(s2_offs === 1.U, s2_rdata(127, 64), s2_rdata(63, 0))
      } .otherwise {
        in.resp.bits.rdata := Mux(s2_offs === 1.U, s2_reg_rdata(127, 64), s2_reg_rdata(63, 0))
      }
      when (RegNext(pipeline_fire)) {
        when (s2_hit) {
          updatePlruTree(s2_idx, s2_way)
        }
        when (s2_hit && s2_wen) {
          for (i <- 0 until 4) {
            when (s2_way === i.U) {
              sram(i).io.en := true.B
              sram(i).io.wen := true.B
              sram(i).io.addr := s2_idx
              sram(i).io.wdata := Mux(s2_offs === 1.U,
                                      Cat(MaskData(s2_rdata(127, 64), s2_wdata, MaskExpand(s2_wmask)), s2_rdata(63, 0)),
                                      Cat(s2_rdata(127, 64), MaskData(s2_rdata(63, 0), s2_wdata, MaskExpand(s2_wmask))))
              meta(i).io.idx := s2_idx
              meta(i).io.dirty_wen := true.B
              meta(i).io.dirty_w := true.B
            }
          }
          state := s_complete
        } .elsewhen (!s2_hit) {
          state := s_miss_req_r
        }
      }
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
      for (i <- 0 until 4) {
        when (replace_way === i.U) {
          sram(i).io.en := true.B
          sram(i).io.wen := true.B
          sram(i).io.addr := s2_idx
          when (s2_wen) {
            sram(i).io.wdata := Mux(s2_offs === 1.U,
                                    Cat(MaskData(wdata2, s2_wdata, MaskExpand(s2_wmask)), wdata1),
                                    Cat(wdata2, MaskData(wdata1, s2_wdata, MaskExpand(s2_wmask))))
          } .otherwise {
            sram(i).io.wdata := Cat(wdata2, wdata1)
          }
          // write allocate
          meta(i).io.idx := s2_idx
          meta(i).io.tag_wen := true.B
          meta(i).io.tag_w := s2_tag
          meta(i).io.dirty_wen := true.B
          meta(i).io.dirty_w := s2_wen
        }
      }
      when (s2_reg_dirty) {
        state := s_miss_req_w1
      } .otherwise {
        updatePlruTree(s2_idx, replace_way)
        state := s_complete
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
      s2_wen := false.B
      when (out.resp.fire()) {
        updatePlruTree(s2_idx, replace_way)
        state := s_complete
      }
    }
    is (s_complete) {
      in.resp.bits.rdata := RegNext(Mux(s2_offs.asBool(), wdata2, wdata1))
      when (in.resp.fire()) {
        state := s_invalid
      }
    }
  }

  when (pipeline_fire) {
    state := Mux(s1_valid, s_idle, s_invalid)
  }

}
