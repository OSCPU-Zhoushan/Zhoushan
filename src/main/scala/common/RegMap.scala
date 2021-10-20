/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package zhoushan

import chisel3._
import chisel3.util._

// ref: NutShell

object RegMap {
  def apply(addr: UInt, reg: UInt, wfn: UInt => UInt = (x => x)) = (addr, (reg, wfn))

  def access(mapping: Map[UInt, (UInt, UInt => UInt)], addr: UInt, rdata: UInt,
             wdata: UInt, wmask: UInt, wen: Bool): Unit = {
    mapping.map { case (a, (r, wfn)) => {
      when (addr === a) {
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

object MaskedRegMap {
  // Unwritable is null for part of read-only CSR registers
  def Unwritable = null

  // default write function
  def NoSideEffect: UInt => UInt = (x => x)

  // default r/w mask
  def DefaultMask = Fill(64, 1.U)

  def apply(addr: UInt, reg: UInt, wmask: UInt = DefaultMask,
            wfn: UInt => UInt = (x => x), rmask: UInt = DefaultMask) = (addr, (reg, wmask, wfn, rmask))

  def access(mapping: Map[UInt, (UInt, UInt, UInt => UInt, UInt)], addr: UInt, rdata: UInt,
             wdata: UInt, wen: Bool): Unit = {
    mapping.map { case (a, (r, wm, wfn, rm)) => {
      when (addr === a) {
        rdata := r & rm
      }
      if (wfn != null) {
        when (addr === a && wen) {
          r := wfn(MaskData(r, wdata, wm))
        }
      }
    }}
  }
}
