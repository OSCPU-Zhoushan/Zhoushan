package zhoushan

import chisel3._
import chisel3.util._

// ref: NutShell

object LookupTable {
  def apply[T <: Data](key: UInt, mapping: Iterable[(UInt, T)]) : T =
    Mux1H(mapping.map(p => (p._1 === key, p._2)))
}

object RegMap {
  def apply(addr: UInt, reg: UInt, wfn: UInt => UInt = (x => x)) = (addr, (reg, wfn))
  def access(mapping: Map[UInt, (UInt, UInt => UInt)], addr: UInt, rdata: UInt, ren: Bool,
             wdata: UInt, wmask: UInt, wen: Bool): Unit = {
    mapping.map { case (a, (r, wfn)) => {
      when (addr === a && ren) {
        rdata := r
      }
      if (wfn != null) {
        when (addr === a && wen) {
          r := wfn(MaskData(r, wdata, wmask))
        }
      }
    }}
  }
}
