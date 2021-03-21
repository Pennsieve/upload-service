// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.routing

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.{ NoContent, OK }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import cats.implicits._
import com.blackfynn.auth.middleware.Jwt.Claim
import com.blackfynn.service.utilities.{ ContextLogger, Tier }
import com.blackfynn.upload.model.ImportId
import com.blackfynn.upload.{ Status, StatusPorts, UploadConfig, UploadLogContext, UploadPorts }
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.ExecutionContext

object StatusRouting {

  type Status = StatusRouting.type
  implicit val tier: Tier[Status] = Tier[Status]

  def apply(
    claim: Claim
  )(implicit
    ec: ExecutionContext,
    ports: UploadPorts,
    s3Settings: S3Settings,
    config: UploadConfig,
    mat: Materializer,
    log: ContextLogger
  ): Route = {
    implicit val statusPorts: StatusPorts = ports.status

    path("status" / baseUploadPath) { (_, importIdString) =>
      complete {
        (ImportId(importIdString), getUserInfo(claim)) match {
          case (Right(importId), Some((_, userId))) => {
            implicit val context: UploadLogContext = UploadLogContext(importId, userId)
            Status(importId, userId)
              .fold[ToResponseMarshallable](
                handleExceptions,
                missingParts =>
                  if (missingParts.files.isEmpty) NoContent
                  else OK -> missingParts
              )
          }

          case (_, None) => unauthorizedResponse

          case (Left(ex), _) => invalidImportIdResponse(importIdString, ex)

          case _ => badRequest()
        }
      }
    }
  }
}
