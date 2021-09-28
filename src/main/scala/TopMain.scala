package zhoushan

object TopMain extends App {
  if (ZhoushanConfig.EnableDifftest) {
    (new chisel3.stage.ChiselStage).execute(args, Seq(
      chisel3.stage.ChiselGeneratorAnnotation(() => new SimTop())
    ))
  } else {
    if (ZhoushanConfig.TargetOscpuSoc) {
      (new chisel3.stage.ChiselStage).execute(args, Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => new RealTop()),
        firrtl.stage.RunFirrtlTransformAnnotation(new AddModulePrefix()),
        ModulePrefixAnnotation(s"ysyx_${ZhoushanConfig.OscpuId}_")
      ))
    } else {
      (new chisel3.stage.ChiselStage).execute(args, Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => new RealTop())
      ))
    }
  }
}
