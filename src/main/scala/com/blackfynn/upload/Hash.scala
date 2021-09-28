// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import java.security.MessageDigest

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import cats.data.{ EitherT, NonEmptyList }
import cats.implicits._
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException
import com.pennsieve.service.utilities.{ ContextLogger, Tier }
import com.blackfynn.upload.model.Eventual.Eventual
import com.blackfynn.upload.model._

import scala.concurrent.{ ExecutionContext, Future }

/**
  * Given an import ID and the name of a file that is part of the import, find all chunks associated with the file,
  * fetch their hash values, and compute a final hash for the file based on the hashes.
  */
object Hash {

  def apply(
    importId: ImportId,
    fileName: String
  )(implicit
    ports: HashPorts,
    ec: ExecutionContext,
    mat: Materializer,
    log: ContextLogger,
    logContext: UploadLogContext,
    tier: Tier[_]
  ): Eventual[FileHash] = {

    for {

      // Given a import ID, fetch the cached preview that contains a listing of all files that were part of the preview:
      preview <- ports.getPreview(importId)

      // Sanity check that the file exists in the cached preview:
      file <- {
        preview.files.find(_.fileName == fileName) match {
          case Some(file) => EitherT.pure[Future, Throwable](file)
          case None =>
            log.tierContext.warn(
              s"File $fileName was not found in the preview list ${preview.files}"
            )
            EitherT.leftT[Future, PreviewFile](FileNotInPreview)
        }

      }

      // If the upload payload is chunked, generate a separate triple (zero-based chunk ID, import ID, file name)
      // for each chunk needed:
      hashIds <- {
        val maybeHashIds =
          file.chunkedUpload
            .flatMap { chunkedUpload: ChunkedUpload =>
              NonEmptyList
                .fromList(
                  (0 until chunkedUpload.totalChunks)
                    .map(ChunkHashId(_, importId, fileName))
                    .toList
                )
            }

        EitherT.fromOption[Future](maybeHashIds, NonChunkedUploadsUnsupported)
      }

      // Get the cached, computed hashes for each chunk as a stream:
      hashes = ports.getChunkHashes(hashIds, importId)

      // If there's only one chunk, return as-is:
      fileHash <- Eventual {
        if (hashIds.length == 1) {
          hashes
            .map {
              case Right(chunkHash) => FileHash(chunkHash.hash).asRight[Throwable]
              case Left(e: ResourceNotFoundException) => MissingHashes(e.getErrorMessage).asLeft
              case Left(unexpectedError) => unexpectedError.asLeft
            }
            .runWith(Sink.head)
            .recover {
              case e => e.asLeft
            }
        } else {
          val emptyDigest = MessageDigest.getInstance("SHA-256")

          // Generate a hash-of-hashes, using the previous hash values for each chunk:
          hashes
            .runWith {
              Sink.fold(emptyDigest.asRight[Throwable]) {
                case (Left(e), _) => e.asLeft[MessageDigest]

                case (Right(digest), Right(ch)) =>
                  digest.update(ch.hash.getBytes())
                  digest.asRight

                case (_, Left(e: ResourceNotFoundException)) =>
                  MissingHashes(e.getErrorMessage).asLeft
                case (_, Left(unexpectedError)) => unexpectedError.asLeft
              }
            }
            .map(_.map(digest => FileHash(digest.digest())))
            .recover {
              case e => e.asLeft
            }
        }
      }

    } yield fileHash
  }
}

case object FileNotInPreview extends Exception("The requested file does not exist in the preview")

case object NonChunkedUploadsUnsupported extends Exception("Non chunked uploads are not supported")

case class MissingHashes(message: String) extends Exception(message)
