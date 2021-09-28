// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RequestContext
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import akka.stream.alpakka.s3.impl.S3Location
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.service.utilities.{ ContextLogger, Tier }
import com.blackfynn.upload.Status.{ createListRequest, getAllParts, InvalidPreviewException }
import com.blackfynn.upload.alpakka.S3Requests.{ completeMultipartUploadRequest, headObjectRequest }
import com.blackfynn.upload.alpakka.Signer._
import com.blackfynn.upload.model.Converters.multipartUpload
import com.blackfynn.upload.model.Eventual._
import com.blackfynn.upload.model.FilesMissingParts._
import com.blackfynn.upload.model._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.parser.decode
import io.circe.syntax.EncoderOps

import scala.concurrent.ExecutionContext

object Complete {

  def apply(
    uriFromName: String => UploadUri,
    importId: ImportId,
    completeRequestParameters: (String, Option[PackageId], Boolean)
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ctx: RequestContext,
    ports: UploadPorts,
    config: UploadConfig,
    log: ContextLogger,
    context: UploadLogContext,
    tier: Tier[_],
    settings: S3Settings
  ): Eventual[HttpResponse] = {

    val ports_ = ports

    for {
      // Download the cached preview
      preview <- ports.complete.getPreview(importId)

      _ = log.tierContext.info(s"Complete: found preview for $importId")

      files = preview.files

      _ = log.tierContext.info(s"Complete: files $files")

      // Complete the multipart uploads
      maybeFilesMissingParts <- completeOngoingMultipartUploads(uriFromName, files)

      _ = log.tierContext.info(s"Complete: checked for missing parts for $importId")

      (datasetId, maybeDestinationId, append) = completeRequestParameters

      maybeNewRootPreview = {
        maybeDestinationId match {
          case Some(destinationCollection: CollectionId) =>
            (preview.metadata.parent, preview.metadata.ancestors.flatMap(_.headOption)) match {
              case (None, None) => preview

              case (_, Some(rootAncestor)) =>
                val alteredRootAncestor = rootAncestor.copy(parentId = Some(destinationCollection))
                preview.copy(
                  metadata = preview.metadata.copy(
                    ancestors = preview.metadata.ancestors
                      .map(ancestors => alteredRootAncestor :: ancestors.tail)
                  )
                )

              case (Some(parent), None) =>
                preview.copy(
                  metadata = preview.metadata
                    .copy(parent = Some(parent.copy(parentId = Some(destinationCollection))))
                )
            }

          case Some(_) | None => preview
        }
      }

      destinationId = maybeDestinationId.fold("")(id => s"&destinationId=$id")

      parameters = s"datasetId=$datasetId&append=$append$destinationId&uploadService=true&hasPreview=true"

      uri = Uri(s"${config.apiBaseUrl}/files/upload/complete/$importId?$parameters")

      originalRequest = ctx.request

      proxyLink <- {
        if (originalRequest.entity.contentLengthOption.exists(_ > 0)) {
          EitherT {
            originalRequest.entity.dataBytes
              .runFold(ByteString.empty)(_ ++ _)
              .map { bytes =>
                decode[ProxyLink](bytes.utf8String).map(_.some)
              }
          }
        } else {
          eventual(Option.empty[ProxyLink])
        }
      }

      _ = log.tierContext.info(s"Complete: created proxy link $proxyLink for $uri")

      // Generate a mapping of file names -> file hashes:
      hashes <- preview.files
        .map { (f: PreviewFile) =>
          {
            implicit val ports: HashPorts = ports_.hash
            Hash(preview.metadata.importId, f.fileName).map { fileHash: FileHash =>
              (f.fileName, fileHash)
            }
          }
        }
        .sequence
        .map(_.toMap)

      entity <- {
        Marshal(UploadCompleteRequest(maybeNewRootPreview.toApiPreview(hashes), proxyLink).asJson)
          .to[RequestEntity]
          .toEventual
      }

      _ = log.tierContext.info(s"Complete: created entity $entity for $uri")

      // If parts are missing in the multipart upload return 404
      response <- {
        maybeFilesMissingParts match {
          case Some(filesMissingParts) =>
            log.tierContext.error(
              s"Complete: found ${filesMissingParts.files.length} missing parts for $importId"
            )

            eventual(routing.notFound(filesMissingParts.asJson.noSpaces))

          case None =>
            // Do not forward the Host header - this determines where we send the request
            val headers = originalRequest.headers.filter(_.isNot("host"))
            val request = HttpRequest(originalRequest.method, uri, headers, entity)

            log.tierContext.info(s"Complete: sending API complete request for $uri")

            ports.complete.sendComplete(request)
        }
      }

      _ = log.tierContext.info(s"Complete: responding with status=${response.status} for $uri")
    } yield {
      // if the response is encoded, pass that header along
      val encodingHeader = response.headers.filter(_.is("content-encoding"))
      HttpResponse(response.status, encodingHeader, response.entity)
    }
  }

  private def completeOngoingMultipartUploads(
    uriFromName: String => UploadUri,
    files: List[PreviewFile]
  )(implicit
    config: UploadConfig,
    ec: ExecutionContext,
    s3Settings: S3Settings,
    ports: UploadPorts,
    mat: Materializer
  ) =
    EitherT {
      Source(files)
        .mapAsyncUnordered(config.complete.parallelism) { file =>
          val maybeIdAndChunkTotal =
            for {
              multipartId <- file.multipartUploadId
              totalChunks <- file.chunkedUpload.map(_.totalChunks)
            } yield (multipartId, totalChunks)

          maybeIdAndChunkTotal
            .fold(eventual[Option[FileMissingParts]](InvalidPreviewException)) {
              case (multipartId, totalChunks) =>
                val uri: UploadUri = uriFromName(file.fileName)

                completeUploadForFile(uri, multipartId, totalChunks)
            }
            .value
        }
        .runWith(Sink.seq)
        .map(_.toList.sequence)
        .map { errorOrMaybeMissingParts =>
          errorOrMaybeMissingParts
            .map { maybeMissingParts =>
              maybeMissingParts.sequence
                .map(FilesMissingParts.apply)
            }
        }
    }

  private def completeUploadForFile(
    uri: UploadUri,
    multipartId: MultipartUploadId,
    totalChunks: Int
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: UploadPorts,
    config: UploadConfig,
    settings: S3Settings
  ): Eventual[Option[FileMissingParts]] =
    for {
      // Get all the parts in the multipart upload
      maybeParts <- {
        getAllParts(
          uri.name,
          totalChunks,
          createListRequest(uri, multipartId),
          ports.complete.listParts
        )
      }
      result <- {
        maybeParts match {
          case Some(Right(parts)) =>
            // send the multipart complete request to S3 with all parts provided they all exist
            val etagsWithIndex = parts.map { case (index, etag, _) => (index, etag) }
            completeMultipartUploadRequest(multipartUpload(uri, multipartId), etagsWithIndex).toEventual
              .flatMap(request => createSignedRequestT(request))
              .flatMap(ports.complete.completeUpload)
              .map(_ => None)

          // inform the client that S3 does not have specific parts
          case Some(Left(missingParts)) => eventual(missingParts.some)

          case None =>
            createSignedRequestT(headObjectRequest(S3Location(config.s3.bucket, uri.toString)))
              .flatMap(ports.complete.checkFileExists)
              .subflatMap { exists =>
                if (exists) Option.empty[FileMissingParts].asRight
                else InvalidPreviewException.asLeft
              }
        }
      }
    } yield result

}
