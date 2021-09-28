// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.routing

import java.util.concurrent.TimeoutException

import akka.actor.Scheduler
import akka.http.scaladsl.common.ToNameReceptacleEnhancements._symbol2NR
import akka.http.scaladsl.model.StatusCodes.{ Created, InternalServerError }
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ RequestContext, Route }
import akka.pattern.retry
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.service.utilities.{ ContextLogger, Tier }
import com.blackfynn.upload.model.Eventual.Eventual
import com.blackfynn.upload.model.{ ImportId, MultipartUploadId, UploadUri }
import com.blackfynn.upload.send.StreamedChunkUpload.sendChunk
import com.blackfynn.upload.{ ChunkPorts, LoadMonitor, UploadConfig, UploadLogContext, UploadPorts }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object StreamedChunkRouting {

  private val chunkedPathParameters =
    ('filename, 'chunkNumber.as[Int], 'multipartId, 'chunkChecksum)

  type StreamedChunk = StreamedChunkRouting.type
  implicit val tier: Tier[StreamedChunk] = Tier[StreamedChunk]

  def apply(
    claim: Jwt.Claim
  )(implicit
    ec: ExecutionContext,
    ctx: RequestContext,
    mat: Materializer,
    scheduler: Scheduler,
    ports: UploadPorts,
    config: UploadConfig,
    log: ContextLogger,
    settings: S3Settings,
    loadMonitor: LoadMonitor
  ): Route =
    withSizeLimit(config.maxChunkSize) {
      (path("chunk" / baseUploadPath) & parameters(chunkedPathParameters)) {
        (
          _,
          importIdString: String,
          filename: String,
          chunkNumber: Int,
          multipartUploadId: String,
          chunkChecksum: String
        ) =>
          {
            extractRequest { request =>
              {
//                implicit val chunkPorts: ChunkPorts = ports.chunk

                log.noContext.info("StreamedChunkRouting: received request")

                (ImportId(importIdString), getUserInfo(claim)) match {

                  case (Right(importId), Some((_, userId))) => {

                    implicit val context: UploadLogContext =
                      UploadLogContext(importId, userId, filename)
                    val id = MultipartUploadId(multipartUploadId)

                    log.tierContext.info(
                      s"StreamedChunkRouting: UploadUri=${UploadUri}; MultipartUploadId=${id}"
                    )

                    if (loadMonitor.canReserve(config.maxChunkSize)) {

                      log.tierContext.info(
                        s"StreamedChunkRouting: reserved ${config.maxChunkSize} bytes"
                      )

                      val chunk: Future[ByteString] =
                        request.entity.dataBytes
                          .completionTimeout(3 minutes)
                          .runFold(ByteString.empty) { (acc, chunk) =>
                            acc ++ chunk
                          }

                      onComplete(chunk) {
                        {
                          case Failure(e) => {
                            log.tierContext.error(s"StreamedChunkRouting: chunk read failure", e)

                            request.discardEntityBytes()
                            loadMonitor.decrement(config.maxChunkSize)

                            e match {
                              // For timeout-specific errors, treat the issue as a failure due to excessive server load:
                              case _: TimeoutException => complete(StatusCodes.TooManyRequests)
                              case _ => failWith(e)
                            }
                          }

                          case Success(chunk) => {

                            complete {

                              implicit val chunkPorts: ChunkPorts = ports.chunk

                              log.tierContext.info(
                                s"StreamedChunkRouting: chunk read success (chunk-size: ${chunk.size})"
                              )

                              (ImportId(importIdString), getUserInfo(claim)) match {
                                case (Right(importId), Some((_, userId))) => {
                                  implicit val context: UploadLogContext =
                                    UploadLogContext(importId, userId, filename)
                                  val id = MultipartUploadId(multipartUploadId)
                                  val uri = UploadUri(userId, importId, filename)

                                  log.tierContext.info(
                                    s"StreamedChunkRouting: UploadUri=${UploadUri}; MultipartUploadId=${id}"
                                  )

                                  val sizeDiff = config.maxChunkSize - chunk.size.toLong
                                  // If 0, this is a no-op since there's nothing to subtract:
                                  if (sizeDiff > 0) {
                                    loadMonitor.decrement(sizeDiff)
                                  }

                                  val retryableChunkSend: Eventual[Unit] =
                                    EitherT(
                                      retry(
                                        attempt = () =>
                                          sendChunk(uri, id, chunkNumber, chunk, chunkChecksum).value,
                                        attempts = 3,
                                        delay = 2 seconds
                                      )
                                    )

                                  retryableChunkSend
                                    .fold(
                                      e => {
                                        log.tierNoContext.error(
                                          s"StreamedChunkRouting: (inner) send failed with",
                                          e
                                        )

                                        loadMonitor.decrement(chunk.size.toLong)
                                        request.discardEntityBytes()

                                        e match {
                                          case _: TimeoutException => tooManyRequests
                                          case _ =>
                                            HttpResponse(
                                              InternalServerError,
                                              entity = createUploadResponse(
                                                success = false,
                                                Some(e.getMessage)
                                              )
                                            )
                                        }
                                      },
                                      _ => {
                                        log.tierContext.info(
                                          s"StreamedChunkRouting: chunk sent successfully (chunk-size: ${chunk.size})"
                                        )

                                        loadMonitor.decrement(chunk.size.toLong)

                                        HttpResponse(
                                          Created,
                                          entity = createUploadResponse(success = true)
                                        )
                                      }
                                    )
                                    .recoverWith {
                                      case e: Exception => {
                                        log.tierNoContext
                                          .error(
                                            s"StreamedChunkRouting: (future) send failed with",
                                            e
                                          )
                                        loadMonitor.decrement(config.maxChunkSize)
                                        request.discardEntityBytes()

                                        Future.failed(e)
                                      }
                                    }
                                }
                              }
                            }
                          }
                        }
                      }
                    } else {
                      request.discardEntityBytes()
                      complete(StatusCodes.TooManyRequests)
                    }
                  }

                  case (_, None) => complete(unauthorizedResponse)

                  case (Left(err), Some(_)) =>
                    complete(invalidImportIdResponse(importIdString, err))

                  case _ => complete(badRequest())
                }
              }
            }
          }
      }
    }
}
