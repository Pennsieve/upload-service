// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.routing

import java.nio.BufferOverflowException

import akka.http.scaladsl.model.StatusCodes.{ Created, InternalServerError }
import akka.http.scaladsl.model.{ HttpResponse, Multipart, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MarshallingDirectives.as
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import cats.implicits._
import com.pennsieve.auth.middleware.Jwt.Claim
import com.pennsieve.service.utilities.{ ContextLogger, LogContext, Tier }
import com.blackfynn.upload.model.{ ImportId, MultipartUploadId }
import com.blackfynn.upload.send.FineUploaderChunkUpload.sendChunk
import com.blackfynn.upload.send.{ ChunkSizeTooLargeException, MissingParameter }
import com.blackfynn.upload.{ ChunkPorts, LoadMonitor, UploadConfig, UploadLogContext, UploadPorts }

import scala.concurrent.{ ExecutionContext, Future, TimeoutException }

object FineUploaderChunkRouting {

  private val chunkedPathParameters = "multipartId"
  private val pathWithParameters =
    path("fineuploaderchunk" / baseUploadPath) & parameters(chunkedPathParameters)

  type FineUploaderChunk = FineUploaderChunkRouting.type
  private implicit val tier: Tier[FineUploaderChunk] = Tier[FineUploaderChunk]

  def apply(
    claim: Claim
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: UploadPorts,
    config: UploadConfig,
    log: ContextLogger,
    s3Settings: S3Settings,
    loadMonitor: LoadMonitor
  ): Route =
    withSizeLimit(config.maxChunkSize) {

      (pathWithParameters & entity(as[Multipart.FormData])) {

        (_, importIdString, multipartUploadId, formData) =>
          {
            extractRequest { request =>
              {
                implicit val chunkPorts: ChunkPorts = ports.chunk

                log.noContext.info("FineUploaderChunkRouting: received request")

                (ImportId(importIdString), getUserInfo(claim)) match {

                  case (Right(importId), Some((_, userId))) => {

                    implicit val context: LogContext = UploadLogContext(importId, userId)

                    val id = MultipartUploadId(multipartUploadId)

                    if (loadMonitor.canReserve(config.maxChunkSize)) {
                      complete {
                        sendChunk(formData, userId, importId, id)
                          .fold(
                            e => {
                              log.tierContext
                                .error(s"FineUploaderChunkUpload: (inner) send failed with", e)

                              request.entity.discardBytes()
                              loadMonitor.decrement(config.maxChunkSize)

                              e match {
                                case _: TimeoutException => tooManyRequests
                                case ChunkSizeTooLargeException =>
                                  badRequest(ChunkSizeTooLargeException.getMessage)
                                case p: MissingParameter =>
                                  badRequest(p.toString())
                                case _: BufferOverflowException =>
                                  badRequest(
                                    "FineUploaderChunkUpload: Chunk sent was larger than chunk size specified"
                                  )
                                case _ => {
                                  HttpResponse(
                                    InternalServerError,
                                    entity =
                                      createUploadResponse(success = false, Some(e.getMessage))
                                  )
                                }
                              }
                            },
                            (result) => {
                              val (bytesSent, chunkHash) = result
                              log.tierContext
                                .info(s"FineUploaderChunkUpload: succeeded for $importId")

                              loadMonitor.decrement(bytesSent.toLong)

                              HttpResponse(Created, entity = createUploadResponse(success = true))
                            }
                          )
                          .recoverWith {
                            case e: Exception => {
                              log.tierNoContext
                                .error(s"FineUploaderChunkUpload: (future) send failed with", e)
                              request.entity.discardBytes()
                              loadMonitor.decrement(config.maxChunkSize)

                              Future.failed(e)
                            }
                          }
                      }
                    } else {
                      request.discardEntityBytes()
                      complete(StatusCodes.TooManyRequests)
                    }
                  }

                  case (_, None) => complete(unauthorizedResponse)

                  case (Left(ex), _) => complete(invalidImportIdResponse(importIdString, ex))

                  case _ => complete(badRequest())
                }
              }
            }
          }
      }
    }
}
