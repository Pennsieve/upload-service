// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import akka.http.scaladsl.model.HttpRequest
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import akka.stream.scaladsl.{ Sink, Source }
import cats.data.EitherT
import cats.implicits._
import com.blackfynn.auth.middleware.UserId
import com.blackfynn.service.utilities.ContextLogger
import com.blackfynn.upload.UploadPorts.ListParts
import com.blackfynn.upload.alpakka.S3Requests.listMultipartUploadRequest
import com.blackfynn.upload.alpakka.Signer.createSignedRequestT
import com.blackfynn.upload.model.Converters.multipartUpload
import com.blackfynn.upload.model.Eventual.{ eventual, Eventual }
import com.blackfynn.upload.model._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

object Status {

  def apply(
    importId: ImportId,
    userId: UserId
  )(implicit
    config: UploadConfig,
    ports: StatusPorts,
    ec: ExecutionContext,
    s3Settings: S3Settings,
    mat: Materializer,
    log: ContextLogger,
    context: UploadLogContext
  ): Eventual[FilesMissingParts] =
    for {
      preview <- ports.getPreview(importId)

      files = preview.files

      _ = log.context.info(s"Status: files=${files}")

      missingParts <- EitherT {
        Source(files)
          .mapAsyncUnordered(config.status.listPartsParallelism)(
            file => findMissingParts(importId, userId)(file).value
          )
          .runWith(Sink.seq)
          .map(_.toList.sequence.map(_.flatten).map(FilesMissingParts.apply))
      }

      totalMissingPartCount = missingParts.files.foldLeft(0)(
        (acc, f) => acc + f.missingParts.length
      )

      missingPartFilenames = missingParts.files.take(10).map(mfp => mfp.fileName).mkString(", ")

      msg = s"Status: found ${totalMissingPartCount} missing parts for ${missingParts.files.length} files: (${missingPartFilenames})"

      _ = log.context.info(msg)
    } yield missingParts

  private def findMissingParts(
    importId: ImportId,
    userId: UserId
  )(
    file: PreviewFile
  )(implicit
    config: UploadConfig,
    ports: StatusPorts,
    ec: ExecutionContext,
    s3Settings: S3Settings,
    mat: Materializer
  ): Eventual[Option[FileMissingParts]] =
    fileToParts(UploadUri(userId, importId, file.fileName), file, ports.listParts)
      .map(_.flatMap(_.swap.toOption))

  type IndexWithEtagAndSize = (Int, String, Long)

  type MaybeParts = Option[Either[FileMissingParts, List[IndexWithEtagAndSize]]]

  private def fileToParts(
    uri: UploadUri,
    file: PreviewFile,
    listParts: ListParts
  )(implicit
    config: UploadConfig,
    ec: ExecutionContext,
    s3Settings: S3Settings,
    mat: Materializer
  ): Eventual[MaybeParts] = {
    val maybeIdAndChunkTotal =
      for {
        multipartId <- file.multipartUploadId
        totalChunks <- file.chunkedUpload.map(_.totalChunks)
      } yield (multipartId, totalChunks)

    maybeIdAndChunkTotal.fold(eventual[MaybeParts](InvalidPreviewException)) {
      case (multipartId, totalChunks) =>
        getAllParts(file.fileName, totalChunks, createListRequest(uri, multipartId), listParts)
    }
  }

  //TODO fix so that we check first if it's truncated with one request
  def getAllParts(
    filename: String,
    totalChunks: Int,
    chunkNumberToRequest: ChunkNumberToListRequest,
    listParts: ListParts
  )(implicit
    ec: ExecutionContext
  ): Eventual[MaybeParts] =
    (0 to totalChunks / 1000).toList
      .map { index =>
        val startNumber = index * 1000
        getParts(listParts, chunkNumberToRequest, startNumber)
      }
      .sequence
      .subflatMap { listOfListsOfParts =>
        val maybeAllParts: Option[List[IndexWithEtagAndSize]] =
          listOfListsOfParts
            .foldRight(Option.empty[List[IndexWithEtagAndSize]]) {
              case (None, None) => None

              case (None, Some(parts)) => Some(parts)

              case (Some(accParts), Some(parts)) => Some(accParts ++ parts)

              case (accParts, None) => accParts
            }

        maybeAllParts
          .map { allParts =>
            if (allParts.length == totalChunks) {
              allParts
                .asRight[FileMissingParts]
            } else {
              FileMissingParts(filename, allParts.map(_._1).toSet, totalChunks)
                .asLeft[List[IndexWithEtagAndSize]]
            }
          }
          .asRight[Throwable]
      }

  private def getParts(
    listParts: ListParts,
    createRequest: ChunkNumberToListRequest,
    chunkNumber: Int
  )(implicit
    ec: ExecutionContext
  ): Eventual[Option[List[IndexWithEtagAndSize]]] =
    for {
      request <- createRequest(chunkNumber)
      maybeListing <- listParts(request)
      parts = {
        maybeListing
          .map {
            _.getParts.asScala.toList.map(part => (part.getPartNumber, part.getETag, part.getSize))
          }
      }
    } yield parts

  type ChunkNumberToListRequest = Int => Eventual[HttpRequest]

  def createListRequest(
    uri: UploadUri,
    multipartId: MultipartUploadId
  )(implicit
    config: UploadConfig,
    ec: ExecutionContext,
    s3Settings: S3Settings,
    mat: Materializer
  ): ChunkNumberToListRequest =
    chunkNumber =>
      createSignedRequestT(
        listMultipartUploadRequest(multipartUpload(uri, multipartId), chunkNumber)
      )

  case object InvalidPreviewException
      extends Exception("MultipartId or TotalChunks not found in preview")
}
