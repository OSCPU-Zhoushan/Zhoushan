package zhoushan

trait ZhoushanConfig {
  val FetchWidth = 1
  val InstBufferSize = 8
  val DecodeWidth = 1
}

object ZhoushanConfig extends ZhoushanConfig { }
