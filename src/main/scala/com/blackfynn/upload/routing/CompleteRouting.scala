// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.routing

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ RequestContext, Route }
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import cats.implicits._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.service.utilities.{ ContextLogger, Tier }
import com.blackfynn.upload.model._
import com.blackfynn.upload.{ Complete, UploadConfig, UploadLogContext, UploadPorts }

import scala.concurrent.ExecutionContext

object CompleteRouting {
  private val completeParameters =
    parameters("datasetId".as[String], "destinationId".as[String].?, "append".as[Boolean].?)

  type Complete = CompleteRouting.type
  implicit val tier: Tier[Complete] = Tier[Complete]

  def apply(
    claim: Jwt.Claim
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ctx: RequestContext,
    ports: UploadPorts,
    config: UploadConfig,
    log: ContextLogger,
    settings: S3Settings
  ): Route =
    (path("complete" / baseUploadPath) & completeParameters) {
      (_, importIdString, datasetId, maybeDestinationId, maybeAppend) =>
        complete {

          val append = maybeAppend.getOrElse(false)
          val maybeDestination = validDestination(maybeDestinationId, append)

          (ImportId(importIdString), getUserInfo(claim), maybeDestination) match {
            case (Right(importId), Some((_, userId)), Right(destinationId)) =>
              val parameters = (datasetId, destinationId, append)
              val nameToUri: String => UploadUri = name => UploadUri(userId, importId, name)
              implicit val context: UploadLogContext = UploadLogContext(importId, userId)

              Complete(nameToUri, importId, parameters)
                .fold(internalServerError, response => response)

            case (_, None, _) => unauthorizedResponse

            case (_, Some(_), Left(ex)) =>
              badRequest(s"Invalid destinationId: ${ex.getMessage}")

            case (Left(ex), Some(_), _) => invalidImportIdResponse(importIdString, ex)

            case _ => badRequest()
          }
        }
    }

  private def validDestination(
    maybeNodeId: Option[String],
    append: Boolean
  ): Either[Throwable, Option[PackageId]] =
    maybeNodeId match {
      case Some(nodeId) if append =>
        FileId
          .fromString(nodeId)
          .map(_.some)
          .leftMap { invalidNodeCode =>
            CollectionId.fromString(nodeId) match {
              case Right(_) => new Exception("Append must be false for uploading to a collection")
              case _ => invalidNodeCode
            }
          }

      case Some(nodeId) =>
        CollectionId
          .fromString(nodeId)
          .map(_.some)
          .leftMap { invalidNodeCode =>
            FileId.fromString(nodeId) match {
              case Right(_) =>
                new Exception("Set append to true to insert in to package destination")
              case _ => invalidNodeCode
            }
          }

      case None => None.asRight
    }
}
