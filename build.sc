// import mill dependency
import mill._
import mill.scalalib._
import mill.scalalib.TestModule.Utest
// support BSP
import mill.bsp._
// maven repository
import coursier.maven.MavenRepository

trait CommonModule extends ScalaModule {
  override def scalaVersion = "2.12.13"
  override def scalacOptions = Seq("-Xsource:2.11")
  private val macroParadise = ivy"org.scalamacros:::paradise:2.1.1"
  override def compileIvyDeps = Agg(macroParadise)
  override def scalacPluginIvyDeps = Agg(macroParadise)
  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
    )
  }
}

val chisel = Agg(
  ivy"edu.berkeley.cs::chisel3:3.5.0-RC1"
)

object `api-config-chipsalliance` extends CommonModule {
  override def millSourcePath = super.millSourcePath / "design" / "craft"
}

object hardfloat extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "berkeley-hardfloat"
  override def ivyDeps = super.ivyDeps() ++ chisel
}

object `rocket-chip` extends SbtModule with CommonModule {
  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"${scalaOrganization()}:scala-reflect:${scalaVersion()}",
    ivy"org.json4s::json4s-jackson:3.6.1"
  ) ++ chisel
  object macros extends SbtModule with CommonModule
  override def moduleDeps = super.moduleDeps ++ Seq(
    `api-config-chipsalliance`, macros, hardfloat
  )
}

object difftest extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "difftest"
  override def ivyDeps = super.ivyDeps() ++ chisel
}

object Zhoushan extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd
  override def ivyDeps = super.ivyDeps() ++ chisel
  override def moduleDeps = super.moduleDeps ++ Seq(
    `rocket-chip`, hardfloat, difftest
  )

  object test extends Tests {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"edu.berkeley.cs::chiseltest:0.3.3",
    )
  }
}
