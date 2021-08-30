package zhoushan

object TopMain extends App {
  if (Settings.Difftest)
    (new chisel3.stage.ChiselStage).execute(args, Seq(chisel3.stage.ChiselGeneratorAnnotation(() => new SimTop())))
  else
    (new chisel3.stage.ChiselStage).execute(args, Seq(chisel3.stage.ChiselGeneratorAnnotation(() => new FpgaTop())))
}
