import sbt._
import Settings._

lazy val root = project.root
  .setName("volsunga")
  .setDescription("Volsunga build definition")
  .configureRoot
  .aggregate(core)

lazy val core = project.from("core")
  .setName("core")
  .setDescription("Common utilities")
  .setInitialImport()
  .configureModule
  .configureTests()
  .settings(Compile / resourceGenerators += task[Seq[File]] {
    val file = (Compile / resourceManaged).value / "volsunga-version.conf"
    IO.write(file, s"version=${version.value}")
    Seq(file)
  })

addCommandAlias("fullTest", ";test;scalastyle")
addCommandAlias("fullCoverageTest", ";coverage;test;coverageReport;coverageAggregate;scalastyle")
