// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.send

import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpRequest }
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import akka.util.ByteString
import cats.implicits._
import com.blackfynn.upload.alpakka.S3Requests._
import com.blackfynn.upload.alpakka.Signer.createSignedRequestT
import com.blackfynn.upload.model.Converters.multipartUpload
import com.blackfynn.upload.model.Eventual.Eventual
import com.blackfynn.upload.model.{ ChunkHash, ChunkHashId, MultipartUploadId, UploadUri }
import com.blackfynn.upload.{ ChunkPorts, UploadConfig }

import scala.concurrent.ExecutionContext

object StreamedChunkUpload {

  def sendChunk(
    uri: UploadUri,
    multipartId: MultipartUploadId,
    chunkNumber: Int,
    chunk: ByteString,
    chunkHash: String
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: ChunkPorts,
    config: UploadConfig,
    settings: S3Settings
  ): Eventual[Unit] =
    for {
      signReq <- {
        val uploadPart: HttpRequest =
          uploadPartRequest(
            multipartUpload(uri, multipartId),
            chunkNumber + 1, // clients are zero based and S3 is 1 based
            HttpEntity.empty(ContentTypes.`application/octet-stream`)
          )

        createSignedRequestT(uploadPart, Some(chunkHash))
      }

      request = HttpRequest(signReq.method, signReq.uri, signReq.headers, chunk)

      result <- {
        // Future must be instantiated outside a flatMap to run in parallel
        val eventualCacheResult =
          ports.cacheHash(ChunkHash(ChunkHashId(uri, chunkNumber), uri.importId, chunkHash))

        ports.sendChunk(request).flatMap(_ => eventualCacheResult)
      }

    } yield result
}
