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
  def access(mapping: Map[UInt, UInt], raddr: UInt, rdata: UInt, ren: Bool,
             waddr: UInt, wdata: UInt, wen: Bool) : Unit = {
    rdata := Mux(ren, LookupTable(raddr, mapping), 0.U)
    mapping.map { case (a, r) => when (waddr === a && wen) {
      r := wdata
    } }
  }
  def access(mapping: Map[UInt, UInt], addr: UInt, rdata: UInt, ren: Bool,
             wdata: UInt, wen: Bool) : Unit = {
    access(mapping, addr, rdata, ren, addr, wdata, wen)
  }
}
