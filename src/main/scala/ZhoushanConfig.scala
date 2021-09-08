package zhoushan

trait ZhoushanConfig {
  val FetchWidth = 2
  val InstBufferSize = 8
  val DecodeWidth = 1
  val RobSize = 16
}

object ZhoushanConfig extends ZhoushanConfig { }
