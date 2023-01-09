// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.routing

import akka.http.scaladsl.common.ToNameReceptacleEnhancements._symbol2NR
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MarshallingDirectives.{ as, entity }
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import cats.implicits._
import com.pennsieve.auth.middleware.{ DatasetId, Jwt, UserId }
import com.pennsieve.service.utilities.{ ContextLogger, LogContext, Tier }
import com.blackfynn.upload.model._
import com.blackfynn.upload.{ Preview, PreviewPorts, UploadConfig, UploadLogContext, UploadPorts }
import io.circe.parser.decode
import io.circe.syntax.EncoderOps

import scala.concurrent.{ ExecutionContext, Future }

object PreviewRouting {
  type Preview = PreviewRouting.type
  implicit val tier: Tier[Preview] = Tier[Preview]

  private val previewParameters =
    parameters("append".as[Boolean], "dataset_id".as[Int].?, "destinationId".as[String].?)

  def apply(
    claim: Jwt.Claim
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: UploadPorts,
    config: UploadConfig,
    settings: S3Settings,
    log: ContextLogger
  ): Route =
    (path("preview" / "organizations" / Segment) & previewParameters) {
      (_, append, datasetId, maybeDestinationId) =>
        hasUnlockedDatasetAccess(claim, datasetId.map(DatasetId.apply)) { (userId, _) =>
          entity(as[String]) { previewRequest =>
            complete {
              implicit val context: UploadLogContext = UploadLogContext(userId)
              implicit val previewPorts: PreviewPorts = ports.preview
              val partialCreatePreview = createPreview(append, userId) _

              val collectionId = CollectionId(maybeDestinationId)

              (decode[PreviewRequest](previewRequest), collectionId) match {
                case (Right(packagePreviewRequest), None) => {
                  log.tierContext.info(s"PreviewRouting: request=${packagePreviewRequest}")
                  partialCreatePreview(None, packagePreviewRequest)
                }

                case (Right(packagePreviewRequest), Some(Right(destCollectionId))) => {
                  log.tierContext.info(
                    s"PreviewRouting: request=${packagePreviewRequest}; destCollectionId=${destCollectionId}"
                  )
                  partialCreatePreview(Some(destCollectionId), packagePreviewRequest)
                }

                case (_, Some(Left(e))) =>
                  badRequest(s"Failed to deserialize destinationId with ${e.getMessage}")

                case (Left(e), _) =>
                  badRequest(s"Failed to deserialize body with ${e.getMessage}")
              }
            }
          }
        }
    }

  private def createPreview(
    append: Boolean,
    userId: UserId
  )(
    sentParentId: Option[CollectionId],
    packagePreviewRequest: PreviewRequest
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: PreviewPorts,
    config: UploadConfig,
    settings: S3Settings,
    log: ContextLogger,
    context: LogContext,
    tier: Tier[Preview]
  ): Future[ToResponseMarshallable] =
    Preview(userId, packagePreviewRequest, append, sentParentId)
      .fold[ToResponseMarshallable](handleExceptions, previews => {
        val previewResponse =
          PreviewPackageResponse(previews).asJson.noSpaces

        HttpResponse(Created, entity = HttpEntity(previewResponse))
      })
}
