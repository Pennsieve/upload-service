// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance.chunks

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.pennsieve.service.utilities.ContextLogger
import com.pennsieve.test.AwaitableImplicits
import com.blackfynn.upload.ChunkPorts.CacheHash
import com.blackfynn.upload.{ FakeS3Store, LoadMonitor, MockLoadMonitor }
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.TestData._
import com.blackfynn.upload.model.Eventual
import org.scalatest.{ Matchers, WordSpecLike }

import scala.collection.mutable.ArrayBuffer

class CacheChunkHashesSpec
    extends WordSpecLike
    with Matchers
    with ScalatestRouteTest
    with AwaitableImplicits {

  implicit val log: ContextLogger = new ContextLogger()
  implicit val loadMonitor: LoadMonitor = new MockLoadMonitor()

  "A hash of every uploaded chunk" should {
    "be cached for FineUploaderChunks" in {
      val fakeHashStore: ArrayBuffer[String] = ArrayBuffer.empty[String]

      val fakeS3Store = new FakeS3Store()

      val sendChunk = fakeS3Store.sendChunk(simpleCsvUri)

      val cacheHash: CacheHash =
        chunkHash =>
          Eventual.successful {
            fakeHashStore += chunkHash.hash
            ()
          }

      multipartFormRequest(firstByteString) ~> createRoutes(sendChunk, cacheHash) ~> check {
        status shouldBe Created
      }

      fakeHashStore should contain only FirstByteStringHash
    }

    "be cached for a stream chunk request" in {
      val fakeHashStore: ArrayBuffer[String] = ArrayBuffer.empty[String]

      val fakeS3Store = new FakeS3Store()

      val sendChunk = fakeS3Store.sendChunk(simpleCsvUri)

      val cacheHash: CacheHash =
        chunkHash =>
          Eventual.successful {
            fakeHashStore += chunkHash.hash
            ()
          }

      val chunkStreamedUploadRequest =
        createChunkRequest(firstByteString, chunkChecksum = FirstByteStringHash)

      chunkStreamedUploadRequest ~> createRoutes(sendChunk, cacheHash) ~> check {
        status shouldBe Created
      }

      fakeHashStore should contain only FirstByteStringHash
    }
  }
}
