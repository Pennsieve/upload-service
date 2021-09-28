// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest, MediaTypes, Multipart }
import akka.http.scaladsl.server.Route
import akka.stream.alpakka.s3.S3Settings
import akka.util.ByteString
import com.pennsieve.service.utilities.ContextLogger
import com.blackfynn.upload.ChunkPorts.{ CacheHash, SendChunk }
import com.blackfynn.upload.LoadMonitor
import com.blackfynn.upload.StubPorts.{ createPorts, stubUploadConfig }
import com.blackfynn.upload.TestAuthentication.{ createPost, makeToken }
import com.blackfynn.upload.TestData.defaultImportId
import com.blackfynn.upload.model.Eventual
import com.blackfynn.upload.routing.UploadRouting
import com.blackfynn.upload.send.FineUploaderChunkUpload.{
  qqChunkSize,
  qqFile,
  qqFileName,
  qqPartIndex,
  qqTotalFileSize
}

import scala.concurrent.ExecutionContext

package object chunks {

  val firstByteString = ByteString("a,b,c\n1,2,3\n")

  val secondByteString = ByteString("4,5,6\n")

  val simpleCsv = "simple.csv"
  val simpleCsvUri = s"bucket/1/$defaultImportId/$simpleCsv"

  val successfulCacheHash: CacheHash = _ => Eventual.successful(())

  def createRoutes(
    sendChunk: SendChunk,
    cacheHash: CacheHash = successfulCacheHash
  )(implicit
    log: ContextLogger,
    system: ActorSystem,
    s3Settings: S3Settings,
    loadMonitor: LoadMonitor
  ): Route = {
    Route.seal(
      UploadRouting(stubUploadConfig, createPorts(sendChunk = sendChunk, cacheHash = cacheHash))
    )
  }

  val fileName = "testFile"

  def createChunkRequest(
    chunkString: ByteString,
    chunkNumber: Int = 0,
    importId: String = defaultImportId.toString,
    chunkChecksum: String = "chunkchecksum"
  )(implicit
    ec: ExecutionContext
  ): HttpRequest = {
    createPost(
      token = makeToken(),
      entity = HttpEntity.Strict(MediaTypes.`application/octet-stream`, chunkString),
      uri =
        s"/chunk/organizations/node-id/id/$importId?filename=$simpleCsv&chunkNumber=$chunkNumber"
          + s"&multipartId=some_id&chunkChecksum=$chunkChecksum"
    )
  }

  def multipartFormRequest(
    byteString: ByteString,
    chunkNumber: Int = 0,
    importId: String = defaultImportId.toString,
    chunkSize: Int = firstByteString.length,
    totalFileSize: Long = firstByteString.length.toLong,
    uploadedFileName: String = fileName
  )(implicit
    ec: ExecutionContext
  ): HttpRequest = {
    val entity = Multipart.FormData(
      Multipart.FormData.BodyPart(
        qqFileName,
        HttpEntity(MediaTypes.`application/octet-stream`, ByteString(uploadedFileName))
      ),
      Multipart.FormData.BodyPart(
        qqChunkSize,
        HttpEntity(MediaTypes.`application/octet-stream`, ByteString(chunkSize.toString))
      ),
      Multipart.FormData.BodyPart(
        qqPartIndex,
        HttpEntity(MediaTypes.`application/octet-stream`, ByteString(chunkNumber.toString))
      ),
      Multipart.FormData
        .BodyPart(qqFile, HttpEntity(MediaTypes.`application/octet-stream`, byteString)),
      Multipart.FormData
        .BodyPart(
          qqTotalFileSize,
          HttpEntity(MediaTypes.`application/octet-stream`, ByteString(totalFileSize.toString))
        )
    )

    createPost(
      token = makeToken(),
      entity = entity,
      uri = s"/fineuploaderchunk/organizations/node-id/id/$importId?multipartId=some_id"
    )
  }

}
