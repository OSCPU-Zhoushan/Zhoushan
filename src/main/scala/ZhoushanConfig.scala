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
  val SqStoreId = 1
  val SqLoadId = 2
  // Constants
  val FetchWidth = 2
  val DecodeWidth = 2
  val IssueWidth = 3
  val CommitWidth = 2
  // Parameters
  val InstBufferSize = 8
  val RobSize = 16
  val IntIssueQueueSize = 8
  val MemIssueQueueSize = 8
  val PrfSize = 64
  val StoreQueueSize = 4
  // Settings
  var TargetOscpuSoc = false
  var EnableDifftest = false
  var EnableMisRateCounter = false
  var EnableQueueAnalyzer = false
  var ResetPc = "h80000000"
  var OscpuId = "000000"
  def updateSettings() {
    EnableDifftest = !TargetOscpuSoc
    ResetPc = if (TargetOscpuSoc) "h30000000" else "h80000000"
  }
  // Debug Info
  val DebugRename = false
  val DebugRenameVerbose = false
  val DebugIntIssueQueue = false
  val DebugMemIssueQueue = false
  val DebugCommit = false
  val DebugJmpPacket = false
  val DebugSram = false
  val DebugLsu = false
  val DebugStoreQueue = false
  val DebugICache = false
  val DebugDCache = false
  val DebugBranchPredictorRas = false
  val DebugBranchPredictorBtb = false
  val DebugArchEvent = false
  val DebugClint = false
}

object ZhoushanConfig extends ZhoushanConfig { }
