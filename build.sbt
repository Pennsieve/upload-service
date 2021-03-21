lazy val AkkaHttpVersion = "10.1.11"
lazy val AkkaVersion = "2.6.5"
lazy val AwsVersion = "1.11.538"
lazy val CatsVersion = "1.6.0"
lazy val CirceVersion = "0.11.1"
lazy val LogbackVersion = "1.2.3"
lazy val UtilitiesVersion = "0.1.10-SNAPSHOT"

lazy val root = (project in file("."))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    inThisBuild(List(
      organization := "com.blackfynn",
      scalaVersion := "2.12.11",
      version := "0.1.0-SNAPSHOT",
      scalacOptions ++= Seq(
        "-language:implicitConversions",
        "-language:postfixOps",
        "-Ypartial-unification",
        "-Xmacro-settings:materialize-derivations",
        "-Xmax-classfile-name", "100",
        "-feature",
        "-deprecation",
        "-encoding", "UTF-8",
        "-language:experimental.macros",
        "-unchecked",
        "-Xfuture",
        "-Xlint",
        "-Yno-adapted-args",
        "-Ywarn-numeric-widen",
        "-Ywarn-value-discard"
      ),
      credentials += Credentials(
        "Sonatype Nexus Repository Manager",
        "nexus.pennsieve.cc",
        sys.env.getOrElse("PENNSIEVE_NEXUS_USER", "pennsieveci"),
        sys.env.getOrElse("PENNSIEVE_NEXUS_PW", "")
      ),
      resolvers ++= Seq(
        "Pennsieve Releases" at "https://nexus.pennsieve.cc/repository/maven-releases",
        "Pennsieve Snapshots" at "https://nexus.pennsieve.cc/repository/maven-snapshots",
        Resolver.bintrayRepo("hseeberger", "maven"),
        Resolver.jcenterRepo,
        Resolver.bintrayRepo("commercetools", "maven")
      )
    )),
    name := "upload-service",
    headerLicense := Some(HeaderLicense.Custom(
      "Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved."
    )),
    headerMappings := headerMappings.value + (HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment),
    test in assembly := {},
    Test / fork := true,
    Test / testForkedParallel := true,
    libraryDependencies ++= Seq(
      "com.typesafe.akka"          %% "akka-stream"                    % AkkaVersion,
      "com.typesafe.akka"          %% "akka-http"                      % AkkaHttpVersion,
      "de.heikoseeberger"          %% "akka-http-circe"                % "1.27.0",

      "com.amazonaws"               % "aws-java-sdk-s3"                % AwsVersion,
      "com.amazonaws"               % "aws-java-sdk-dynamodb"          % AwsVersion,
      "com.lightbend.akka"         %% "akka-stream-alpakka-s3"         % "1.0-M1",

      "org.apache.commons"          % "commons-io"                     % "1.3.2",

      "com.blackfynn"              %% "service-utilities"              % "1.3.4-SNAPSHOT",
      "com.blackfynn"              %% "utilities"                      % UtilitiesVersion,
      "com.blackfynn"              %% "auth-middleware"                % "4.2.2",

      "com.blackfynn"              %% "core-models"                    % "41-826c2a8",

      "ch.qos.logback"              % "logback-classic"                % LogbackVersion,
      "ch.qos.logback"              % "logback-core"                   % LogbackVersion,
      "net.logstash.logback"        % "logstash-logback-encoder"       % "5.2",

      "io.circe"                   %% "circe-core"                     % CirceVersion,
      "io.circe"                   %% "circe-generic"                  % CirceVersion,
      "io.circe"                   %% "circe-java8"                    % CirceVersion,

      "com.github.pureconfig"      %% "pureconfig"                     % "0.9.1",

      "com.typesafe.scala-logging" %% "scala-logging"                  % "3.9.0",

      "org.typelevel"              %% "cats-core"                      % CatsVersion,

      "com.blackfynn"              %% "utilities"                      % UtilitiesVersion % Test classifier "tests",
      "org.scalatest"              %% "scalatest"                      % "3.0.5"          % Test,
      "com.typesafe.akka"          %% "akka-stream-testkit"            % AkkaVersion      % Test,
      "com.typesafe.akka"          %% "akka-http-testkit"              % AkkaHttpVersion  % Test,
    ),

    coverageExcludedPackages := "com.blackfynn.upload.server\\..*;"
      + "com.blackfynn.upload.clients\\..*;"
      + "com.blackfynn.upload.alpakka\\..*;"
      + "com.blackfynn.upload.Server",
    coverageMinimum := 86,
    coverageFailOnMinimum := true,

    scalafmtOnCompile := true,

    dockerfile in docker := {
      val artifact: File = assembly.value
      val artifactTargetPath = s"/app/${artifact.name}"
      new Dockerfile {
        from("pennsieve/java-cloudwrap:10-jre-slim-0.5.9")
        copy(artifact, artifactTargetPath, chown="pennsieve:pennsieve")
        copy(baseDirectory.value / "bin" / "run.sh", "/app/run.sh", chown="pennsieve:pennsieve")
        run("wget", "-qO", "/app/newrelic.jar", "http://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic.jar")
        cmd("--service", "upload-service", "exec", "app/run.sh", artifactTargetPath)
      }
    },
    imageNames in docker := Seq(
      ImageName("pennsieve/upload-service:latest")
    )
  ).enablePlugins(DockerPlugin)
