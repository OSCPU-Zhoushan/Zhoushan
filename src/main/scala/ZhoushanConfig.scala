package zhoushan

import chisel3.util._

trait ZhoushanConfig {
  // MMIO
  val ClintAddrBase = 0x02000000
  val ClintAddrSize = 0x10000
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
  val PrfAddrSize = log2Up(PrfSize)
}

object ZhoushanConfig extends ZhoushanConfig { }
