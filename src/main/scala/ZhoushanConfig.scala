package zhoushan

trait ZhoushanConfig {
  val FetchWidth = 2
  val InstBufferSize = 8
  val DecodeWidth = 2
  val RobSize = 16
  val IssueQueueSize = 8
  val IssueWidth = 2
  val CommitWidth = 2
}

object ZhoushanConfig extends ZhoushanConfig { }
