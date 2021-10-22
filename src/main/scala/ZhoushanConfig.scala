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

trait ZhoushanConfig {
  // MMIO Address Map
  val ClintAddrBase = 0x02000000
  val ClintAddrSize = 0x10000
  // Bus ID
  val InstCacheId = 1
  val DataCacheId = 2
  val InstUncacheId = 3
  val DataUncacheId = 4
  // Settings
  val TargetOscpuSoc = false
  val EnableDifftest = !TargetOscpuSoc
  val ResetPc = if (TargetOscpuSoc) "h30000000" else "h80000000"
  val OscpuId = "000000"
  // Debug Info
  val DebugCommit = false
  val DebugJmpPacket = false
  val DebugSram = false
  val DebugLsu = false
  val DebugICache = false
  val DebugDCache = false
  val DebugUncache = false
  val DebugArchEvent = false
  val DebugClint = false
  val DebugCrossbar1to2 = false
  val DebugCoreBus = false
}

object ZhoushanConfig extends ZhoushanConfig { }
