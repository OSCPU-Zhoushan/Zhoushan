package zhoushan

object TopMain extends App {
  if (ZhoushanConfig.EnableDifftest) {
    (new chisel3.stage.ChiselStage).execute(args, Seq(
      chisel3.stage.ChiselGeneratorAnnotation(() => new SimTop())
    ))
  } else {
    if (ZhoushanConfig.EnableAddModulePrefix) {
      (new chisel3.stage.ChiselStage).execute(args, Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => new RealTop()),
        firrtl.stage.RunFirrtlTransformAnnotation(new AddModulePrefix()),
        ModulePrefixAnnotation("ysyx_210128_")
      ))
    } else {
      (new chisel3.stage.ChiselStage).execute(args, Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => new RealTop())
      ))
    }
  }
}
