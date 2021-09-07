package zhoushan

trait ZhoushanConfig {
  val FetchWidth = 2
  val InstBufferSize = 8
  val DecodeWidth = 1
}

object ZhoushanConfig extends ZhoushanConfig { }
