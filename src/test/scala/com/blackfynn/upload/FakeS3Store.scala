// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import java.util.UUID

import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import akka.stream.alpakka.s3.impl.{ MultipartUpload, S3Location }
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import com.amazonaws.services.s3.model.{ PartListing, PartSummary }
import com.blackfynn.test.AwaitableImplicits
import com.blackfynn.upload.ChunkPorts.SendChunk
import com.blackfynn.upload.CompletePorts.CompleteUpload
import com.blackfynn.upload.UploadPorts.ListParts
import com.blackfynn.upload.model.Eventual.Eventual
import com.blackfynn.upload.model.Eventual

import scala.collection.convert.ImplicitConversionsToJava._
import scala.collection.mutable

class FakeS3Store extends AwaitableImplicits {
  var storedUploadInitiations: Map[String, HttpRequest] = Map.empty
  var chunks: Map[String, List[ByteString]] = Map.empty

  val completeUploadRequests: mutable.ArrayBuffer[HttpRequest] = mutable.ArrayBuffer.empty
  var markedComplete: Boolean = false

  def sendChunk(uri: String)(implicit mat: ActorMaterializer): SendChunk =
    request => {
      val freshBytes = request.entity.dataBytes.runWith(Sink.seq).awaitFinite().toList

      chunks
        .get(uri)
        .fold(chunks += uri -> freshBytes) { bytes =>
          chunks += uri -> (bytes ++ freshBytes)
        }

      Eventual.successful(())
    }

  def initiateMultipartUpload(request: HttpRequest): Eventual[MultipartUpload] =
    Eventual.successful {
      storedUploadInitiations += request.uri.toString -> request
      MultipartUpload(S3Location("bucket", "key"), UUID.randomUUID().toString)
    }

  def listPartsEmpty: ListParts =
    _ =>
      Eventual.successful {
        val listing = new PartListing()
        listing.setParts(List.empty[PartSummary])

        Option(listing)
      }

  def listParts: ListParts =
    _ =>
      Eventual.successful {
        val partSummary = new PartSummary()
        partSummary.setETag("etag")
        partSummary.setPartNumber(1)

        val listing = new PartListing()
        listing.setParts(List(partSummary))

        Option(listing)
      }

  def completeUpload: CompleteUpload =
    req => {
      completeUploadRequests += req
      Eventual.successful {
        markedComplete = true
      }
    }
}
