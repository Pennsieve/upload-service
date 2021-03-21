// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.send

import java.io.{ BufferedInputStream, ByteArrayInputStream }
import java.security.MessageDigest

import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, Multipart }
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import akka.stream.alpakka.s3.impl.{ MultipartUpload, S3Location }
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import com.blackfynn.auth.middleware.UserId
import com.blackfynn.service.utilities.{ ContextLogger, LogContext, Tier }
import com.blackfynn.upload.alpakka.S3Requests.uploadPartRequest
import com.blackfynn.upload.alpakka.Signer
import com.blackfynn.upload.model.Constants.MaxChunkSize
import com.blackfynn.upload.model.Eventual.Eventual
import com.blackfynn.upload.model.{ ChunkHash, ChunkHashId, ImportId, MultipartUploadId, UploadUri }
import com.blackfynn.upload.{ ChunkPorts, LoadMonitor, UploadConfig }
import org.apache.commons.codec.binary.Hex

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

case class BfChunk(data: ByteString, size: Int, hash: String)

sealed trait FineUploaderParameter
case class QQFileName(value: String) extends FineUploaderParameter
case class QQFile(value: BfChunk) extends FineUploaderParameter
case class QQChunkSize(value: Int) extends FineUploaderParameter
case class QQPartIndex(value: Int) extends FineUploaderParameter
case class QQTotalParts(value: Int) extends FineUploaderParameter
case class QQTotalFileSize(value: Long) extends FineUploaderParameter

final case class FineUploaderPayload(
  fileName: Option[QQFileName],
  file: Option[QQFile],
  chunkSize: Option[QQChunkSize],
  partIndex: Option[QQPartIndex],
  totalParts: Option[QQTotalParts],
  totalFileSize: Option[QQTotalFileSize]
)

object FineUploaderPayload {
  def empty: FineUploaderPayload =
    FineUploaderPayload(
      fileName = None,
      file = None,
      chunkSize = None,
      partIndex = None,
      totalParts = None,
      totalFileSize = None
    )
}

object FineUploaderChunkUpload {
  val qqFileName: String = "qqfilename"
  val qqFile: String = "qqfile"
  val qqChunkSize: String = "qqchunksize"
  val qqPartIndex: String = "qqpartindex"
  val qqTotalParts: String = "qqtotalparts"
  val qqTotalFileSize: String = "qqtotalfilesize"

  val RequiredParts: Set[String] = Set(qqFile, qqFileName, qqChunkSize, qqPartIndex)

  private def toString[T](
    source: Source[ByteString, T]
  )(implicit
    materializer: Materializer
  ): Future[String] =
    source
      .map(_.utf8String)
      .runFold("")((wholeString, string) => wholeString.concat(string))
  val hashDigest: MessageDigest = MessageDigest.getInstance("SHA-256")

  // From https://docs.aws.amazon.com/amazonglacier/latest/dev/amazon-glacier-signing-requests.html#example-signature-calculation-streaming
  private def computeHash(
    hashDigest: MessageDigest,
    bytes: ByteString,
    hashChunkSize: Int = 4096
  ): MessageDigest = {
    val bis = new BufferedInputStream(new ByteArrayInputStream(bytes.toArray))
    val buffer: Array[Byte] = Array.fill[Byte](hashChunkSize)(0)
    var bytesRead: Int = -1
    do {
      bytesRead = bis.read(buffer, 0, buffer.length)
      if (bytesRead != -1) {
        hashDigest.update(buffer, 0, bytesRead)
      }
    } while (bytesRead != -1)
    hashDigest
  }

  def concatFineChunks(): Sink[Any, Future[(ByteString, MessageDigest)]] = {
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    val accumulator: ByteString = ByteString.empty
    val initial: (ByteString, MessageDigest) = (accumulator, digest)
    Sink.fold(initial) {
      case ((accumulated: ByteString, d: MessageDigest), fineChunk: ByteString) => {
        (accumulated ++ fineChunk, computeHash(d, fineChunk))
      }
    }
  }

  def sendChunk(
    formData: Multipart.FormData,
    userId: UserId,
    importId: ImportId,
    multipartUploadId: MultipartUploadId
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: ChunkPorts,
    config: UploadConfig,
    log: ContextLogger,
    context: LogContext,
    tier: Tier[_],
    s3Settings: S3Settings,
    loadMonitor: LoadMonitor
  ): Eventual[(Int, ChunkHash)] = {
    for {
      partRequestWithHash <- EitherT {
        formData.parts
          .completionTimeout(3 minutes)
          .runFoldAsync(FineUploaderPayload.empty) {
            case (payload: FineUploaderPayload, part: FormData.BodyPart) => {
              part.name match {
                case `qqFile` =>
                  part.entity.dataBytes.runWith(concatFineChunks).map {
                    case (bytes: ByteString, digest: MessageDigest) => {
                      loadMonitor.decrement(config.maxChunkSize - bytes.size.toLong)
                      payload
                        .copy(
                          file = QQFile(
                            BfChunk(
                              data = bytes,
                              size = bytes.size,
                              hash = Hex.encodeHexString(digest.digest())
                            )
                          ).some
                        )
                    }
                  }
                case `qqFileName` =>
                  toString(part.entity.dataBytes).map { body: String =>
                    payload.copy(fileName = QQFileName(body).some)
                  }
                case `qqChunkSize` =>
                  toString(part.entity.dataBytes).map { body: String =>
                    payload.copy(chunkSize = QQChunkSize(body.toInt).some)
                  }
                case `qqTotalParts` =>
                  toString(part.entity.dataBytes).map { body: String =>
                    payload.copy(totalParts = QQTotalParts(body.toInt).some)
                  }
                case `qqTotalFileSize` =>
                  toString(part.entity.dataBytes).map { body: String =>
                    payload.copy(totalFileSize = QQTotalFileSize(body.toLong).some)
                  }
                case `qqPartIndex` =>
                  toString(part.entity.dataBytes).map { body: String =>
                    payload.copy(partIndex = QQPartIndex(body.toInt).some)
                  }
                case _ => {
                  // For any other parameters, just ignore and pass on the payload:
                  Future.successful(payload)
                }
              }
            }
          }
          .map { payload: FineUploaderPayload =>
            {
              log.tierContext.info(s"payload = ${payload.fileName}")
              for {
                qqFileName <- payload.fileName
                  .toRight[Throwable](MissingParameter(qqFileName))
                qqChunkSize <- payload.chunkSize
                  .toRight[Throwable](MissingParameter(qqChunkSize))
                  .ensure(ChunkSizeTooLargeException)(
                    (chunkSize: QQChunkSize) => chunkSize.value <= MaxChunkSize
                  )
                qqPartIndex <- payload.partIndex
                  .toRight[Throwable](MissingParameter(qqPartIndex))
                qqFile <- payload.file.toRight[Throwable](MissingParameter(qqFile))

                uploadUri = UploadUri(userId, importId, qqFileName.value)
                bytesSent = payload.file.map(_.value.size).getOrElse(0)

                uploadInfo = {
                  MultipartUpload(
                    S3Location(config.s3.bucket, uploadUri.toString),
                    multipartUploadId.value
                  )
                }
                partRequest = {
                  uploadPartRequest(
                    uploadInfo,
                    qqPartIndex.value + 1, // clients are zero based and S3 is 1 based
                    HttpEntity(ContentTypes.`application/octet-stream`, qqFile.value.data)
                  )
                }
                chunkHash = ChunkHash(
                  ChunkHashId(uploadUri, qqPartIndex.value),
                  uploadUri.importId,
                  qqFile.value.hash
                )

              } yield (partRequest, bytesSent, chunkHash)
            }
          }
      }

      (request, bytesSent, chunkHash) = partRequestWithHash

      signedPartRequest <- {
        Signer.createSignedRequestT(request, Some(chunkHash.hash))
      }

      _ <- {
        // Future must be instantiated outside a flatMap to run in parallel
        val eventualCacheResult = ports.cacheHash(chunkHash)
        ports.sendChunk(signedPartRequest).flatMap(_ => eventualCacheResult)
      }
    } yield (bytesSent, chunkHash)
  }
}

case class MissingParameter(name: String) extends Exception(s"Missing parameter: ${name}")

case object ChunkSizeTooLargeException
    extends Exception(s"Chunks for fineuploader endpoint must be within $MaxChunkSize bytes")
