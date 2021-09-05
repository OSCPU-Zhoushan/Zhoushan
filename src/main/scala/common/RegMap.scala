package zhoushan

import chisel3._
import chisel3.util._

// ref: NutShell

object LookupTable {
  def apply[T <: Data](key: UInt, mapping: Iterable[(UInt, T)]) : T =
    Mux1H(mapping.map(p => (p._1 === key, p._2)))
}

object RegMap {
  def apply(addr: UInt, reg: UInt) = (addr, reg)
  def access(mapping: Map[UInt, UInt], addr: UInt, rdata: UInt, ren: Bool,
             wdata: UInt, wmask: UInt, wen: Bool): Unit = {
    mapping.map { case (a, r) => {
      when (addr === a && ren) {
        rdata := r
      }
      when (addr === a && wen) {
        r := MaskData(r, wdata, wmask)
      }
    }}
  }
}
