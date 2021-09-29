package zhoushan

object TopMain extends App with ZhoushanConfig {

  TargetOscpuSoc = false
  for (i <- 0 until args.length) {
    if (args(i) == "--soc") {
      args(i) = ""
      TargetOscpuSoc = true
    }
  }
  updateSettings()

  if (EnableDifftest) {
    (new chisel3.stage.ChiselStage).execute(args, Seq(
      chisel3.stage.ChiselGeneratorAnnotation(() => new SimTop())
    ))
  } else {
    if (TargetOscpuSoc) {
      (new chisel3.stage.ChiselStage).execute(args, Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => new RealTop()),
        firrtl.stage.RunFirrtlTransformAnnotation(new AddModulePrefix()),
        ModulePrefixAnnotation(s"ysyx_${OscpuId}_")
      ))
    } else {
      (new chisel3.stage.ChiselStage).execute(args, Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => new RealTop())
      ))
    }
  }

}
