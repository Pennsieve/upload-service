// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.routing

import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ EntityStreamSizeException, StatusCodes }
import akka.http.scaladsl.server.Directives.{
  complete,
  path,
  post,
  handleExceptions => handleExceptionsDirective,
  _
}
import akka.http.scaladsl.server.directives.BasicDirectives.extractRequestContext
import akka.http.scaladsl.server.{ ExceptionHandler, RequestContext, Route }
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import com.pennsieve.auth.middleware.AkkaDirective.authenticateJwt
import com.pennsieve.service.utilities.ContextLogger
import com.blackfynn.upload.{ LoadMonitor, UploadConfig, UploadPorts }

import scala.concurrent.{ ExecutionContext, TimeoutException }

object UploadRouting {

  private val healthCheck = (get & path("health"))(complete(OK))

  private val exceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case _: TooManyRequests.type => complete(StatusCodes.TooManyRequests)
      case _: TimeoutException => complete(StatusCodes.TooManyRequests)
      case e: EntityStreamSizeException =>
        complete(StatusCodes.PayloadTooLarge -> e.getMessage)
      case e: Throwable => complete(500 -> e.getMessage)

    }

  def apply(
    config: UploadConfig,
    ports: UploadPorts
  )(implicit
    log: ContextLogger,
    system: ActorSystem,
    s3Settings: S3Settings,
    loadMonitor: LoadMonitor
  ): Route = {
    implicit val implicitPorts: UploadPorts = ports
    implicit val implicitConfig: UploadConfig = config

    Route.seal(healthCheck ~ handleExceptionsDirective(exceptionHandler) {
      extractRequestContext {
        ctx: RequestContext =>
          implicit val requestContext: RequestContext = ctx
          implicit val ec: ExecutionContext = ctx.executionContext
          implicit val mat: Materializer = ctx.materializer
          implicit val scheduler: Scheduler = system.scheduler

          authenticateJwt(system.name)(config.jwt) { claim =>
            post {
              val uploadRoutes = StreamedChunkRouting(claim) ~ FineUploaderChunkRouting(claim)
              uploadRoutes ~ PreviewRouting(claim) ~ CompleteRouting(claim)
            } ~ get {
              StatusRouting(claim) ~ HashRouting(claim)
            }
          } ~ complete(Unauthorized)
      }
    })
  }
}
