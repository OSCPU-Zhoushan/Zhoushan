// import mill dependency
import mill._
import mill.scalalib._
import mill.scalalib.TestModule.Utest
// support BSP
import mill.bsp._
// maven repository
import coursier.maven.MavenRepository

object ivys {
  val sv = "2.12.13"
  val chisel3 = ivy"edu.berkeley.cs::chisel3:3.5.0-RC1"
  val chisel3Plugin = ivy"edu.berkeley.cs:::chisel3-plugin:3.5.0-RC1"
  val chiseltest = ivy"edu.berkeley.cs::chiseltest:0.3.3"
  val scalatest = ivy"org.scalatest::scalatest:3.2.2"
  val macroParadise = ivy"org.scalamacros:::paradise:2.1.1"
}

trait CommonModule extends ScalaModule {
  override def scalaVersion = ivys.sv
  override def scalacOptions = Seq("-Xsource:2.11")
  override def compileIvyDeps = Agg(ivys.macroParadise)
  override def scalacPluginIvyDeps = Agg(ivys.macroParadise, ivys.chisel3Plugin)
}

object difftest extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "difftest"
  override def ivyDeps = super.ivyDeps() ++ Agg(ivys.chisel3)
}

object Zhoushan extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd
  override def ivyDeps = super.ivyDeps() ++ Agg(ivys.chisel3)
  override def moduleDeps = super.moduleDeps ++ Seq(
    difftest
  )

  object test extends Tests {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivys.chiseltest,
      ivys.scalatest,
    )
  }
}
