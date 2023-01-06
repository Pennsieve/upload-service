resolvers ++= Seq(
  "pennsieve-maven-proxy" at "https://nexus.pennsieve.cc/repository/maven-public",
  Resolver.url("pennsieve-ivy-proxy", url("https://nexus.pennsieve.cc/repository/ivy-public/"))( Patterns("[organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]") ),
)

credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "nexus.pennsieve.cc",
  sys.env("PENNSIEVE_NEXUS_USER"),
  sys.env("PENNSIEVE_NEXUS_PW")
)

addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.9.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.7")

addSbtPlugin("com.geirsson" % "sbt-scalafmt" % "1.6.0-RC4")

addSbtPlugin("dev.guardrail" % "sbt-guardrail" % "0.70.0.2")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.3")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.9.3")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.0.0")
