lazy val AkkaHttpVersion = "10.2.9"
lazy val AkkaVersion = "2.6.19"
lazy val AwsVersion = "1.11.538"
lazy val AwsV2Version = "2.15.58"
lazy val CatsVersion = "2.6.1"
lazy val CirceVersion = "0.14.1"
lazy val LogbackVersion = "1.2.3"
lazy val UtilitiesVersion = "4-55953e4"
lazy val coreModelsVersion = "230-d06f311"

lazy val root = (project in file("."))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    inThisBuild(List(
      organization := "com.blackfynn",
      scalaVersion := "2.13.8",
      version := "0.1.0-SNAPSHOT",
      scalacOptions ++= Seq(
        "-language:implicitConversions",
        "-language:postfixOps",
        "-Xmacro-settings:materialize-derivations",
        "-feature",
        "-deprecation",
        "-encoding", "UTF-8",
        "-language:experimental.macros",
        "-unchecked",
        "-Xlint",
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
    assembly / test := {},
    Test / fork := true,
    Test / testForkedParallel := true,
    libraryDependencies ++= Seq(
      "com.typesafe.akka"          %% "akka-stream"                    % AkkaVersion,
      "com.typesafe.akka"          %% "akka-http"                      % AkkaHttpVersion,
      "de.heikoseeberger"          %% "akka-http-circe"                % "1.39.2",

      "software.amazon.awssdk"      % "s3"                             % AwsV2Version,
      "com.amazonaws"               % "aws-java-sdk-dynamodb"          % AwsVersion,
      "com.lightbend.akka"         %% "akka-stream-alpakka-s3"         % "4.0.0",

      "org.apache.commons"          % "commons-io"                     % "1.3.2",

      "com.pennsieve"              %% "service-utilities"              % "9-b838dd9",
      "com.pennsieve"              %% "utilities"                      % UtilitiesVersion,
      "com.pennsieve"              %% "auth-middleware"                % "5.2.0",

      "com.pennsieve"              %% "core-models"                    % coreModelsVersion,

      "ch.qos.logback"              % "logback-classic"                % LogbackVersion,
      "ch.qos.logback"              % "logback-core"                   % LogbackVersion,
      "net.logstash.logback"        % "logstash-logback-encoder"       % "5.2",

      "io.circe"                   %% "circe-core"                     % CirceVersion,
      "io.circe"                   %% "circe-generic"                  % CirceVersion,

      "com.github.pureconfig"      %% "pureconfig"                     % "0.17.1",

      "com.typesafe.scala-logging" %% "scala-logging"                  % "3.9.4",

      "org.typelevel"              %% "cats-core"                      % CatsVersion,

      "com.pennsieve"              %% "utilities"                      % UtilitiesVersion % Test classifier "tests",
      "com.pennsieve"              %% "core-models"                    % coreModelsVersion % Test classifier "tests",

      "org.scalatest"              %% "scalatest"                      % "3.2.12"          % Test,
      "com.typesafe.akka"          %% "akka-stream-testkit"            % AkkaVersion      % Test,
      "com.typesafe.akka"          %% "akka-http-testkit"              % AkkaHttpVersion  % Test
    ),

    coverageExcludedPackages := "com.blackfynn.upload.server\\..*;"
      + "com.blackfynn.upload.clients\\..*;"
      + "com.blackfynn.upload.alpakka\\..*;"
      + "com.blackfynn.upload.Server",
    coverageMinimumStmtTotal := 86,
    coverageFailOnMinimum := true,

    scalafmtOnCompile := true,

    docker / dockerfile := {
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
    docker / imageNames := Seq(
      ImageName("pennsieve/upload-service:latest")
    )
  ).enablePlugins(DockerPlugin)
