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

//ref from BitUtils.scala
object WordShift {
  def apply(data: UInt, wordIndex: UInt, step: Int) = (data << (wordIndex * step.U))
}

object MaskExpand {
 def apply(m: UInt) = Cat(m.asBools.map(Fill(8, _)).reverse)
}

object MaskData {
 def apply(oldData: UInt, newData: UInt, fullmask: UInt) = {
   (newData & fullmask) | (oldData & ~fullmask)
 }
}

object SignExt {
  def apply(a: UInt, len: Int) = {
    val aLen = a.getWidth
    val signBit = a(aLen-1)
    if (aLen >= len) a(len-1,0) else Cat(Fill(len - aLen, signBit), a)
  }
}

object ZeroExt {
  def apply(a: UInt, len: Int) = {
    val aLen = a.getWidth
    if (aLen >= len) a(len-1,0) else Cat(0.U((len - aLen).W), a)
  }
}