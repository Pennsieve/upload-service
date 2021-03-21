// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance.chunks

import akka.http.scaladsl.model.StatusCodes.{
  BadRequest,
  Created,
  PayloadTooLarge,
  TooManyRequests
}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.blackfynn.service.utilities.ContextLogger
import com.blackfynn.test.AwaitableImplicits
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.StubRoutes.stubRoutes
import com.blackfynn.upload.TestData._
import com.blackfynn.upload.model.Constants.MaxChunkSize
import com.blackfynn.upload.{ FakeS3Store, MockLoadMonitor }
import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }

class FineUploaderChunkSpec
    extends WordSpec
    with Matchers
    with ScalatestRouteTest
    with AwaitableImplicits
    with BeforeAndAfterEach {

  implicit val log: ContextLogger = new ContextLogger()
  implicit val loadMonitor: MockLoadMonitor = new MockLoadMonitor()

  override def beforeEach(): Unit = {
    super.beforeEach()
    loadMonitor.reset()
  }

  "A fine uploader chunked upload" should {

    "be rate limited for too many concurrent requests" in {
      val fakeS3Store = new FakeS3Store()
      val uri = s"bucket/1/$defaultImportId/$simpleCsv"
      val sendChunk = fakeS3Store.sendChunk(uri)

      val routes = createRoutes(sendChunk)

      multipartFormRequest(firstByteString, 0) ~> routes ~> check {
        status shouldBe Created
        multipartFormRequest(firstByteString, 1) ~> routes ~> check {
          status shouldBe Created
          loadMonitor.disallow()
          multipartFormRequest(firstByteString, 2) ~> routes ~> check {
            status shouldBe TooManyRequests
          }
        }
      }
    }

    "handle non-escaped fileNames" in {
      val fakeS3Store = new FakeS3Store()
      val uri = s"bucket/1/$defaultImportId/test%2Bupload.csv"
      val sendChunk = fakeS3Store.sendChunk(uri)

      val routes = createRoutes(sendChunk)

      multipartFormRequest(
        byteString = firstByteString,
        chunkNumber = 0,
        uploadedFileName = "test+upload.csv"
      ) ~> routes ~> check {
        status shouldBe Created
      }
    }

    "fail if the chunk payload is too large" in {
      val fakeS3Store = new FakeS3Store()
      val uri = s"bucket/1/$defaultImportId/$simpleCsv"
      val sendChunk = fakeS3Store.sendChunk(uri)

      multipartFormRequest(tooMuchData, 0) ~> createRoutes(sendChunk) ~> check {
        status shouldBe PayloadTooLarge
      }
    }

    "parse total file sizes larger than Int.MaxValue" in {
      val fakeS3Store = new FakeS3Store()
      val uri = s"bucket/1/$defaultImportId/$simpleCsv"
      val sendChunk = fakeS3Store.sendChunk(uri)

      multipartFormRequest(firstByteString, totalFileSize = 4280827722L) ~> createRoutes(sendChunk) ~> check {
        status shouldBe Created
      }
    }

    "append the uploaded chunks to an ongoing upload" in {
      val fakeS3Store = new FakeS3Store()
      val uri = s"bucket/1/$defaultImportId/$simpleCsv"
      val sendChunk = fakeS3Store.sendChunk(uri)
      val expectedChunks = List(firstByteString, secondByteString)

      multipartFormRequest(firstByteString, 0) ~> createRoutes(sendChunk) ~> check {
        status shouldBe Created
      }

      multipartFormRequest(secondByteString, 1) ~> createRoutes(sendChunk) ~> check {
        status shouldBe Created
      }

      fakeS3Store.chunks(uri) shouldBe expectedChunks
    }

    "return bad request for a request with an invalid importId" in {
      val requestInvalidImportId =
        multipartFormRequest(firstByteString, importId = "invalid-import-id")

      requestInvalidImportId ~> stubRoutes ~> check {
        status shouldBe BadRequest
      }
    }

    "return bad request for chunkSizes greater than 20MB" in {
      val fortyMegabyteRequest =
        multipartFormRequest(firstByteString, chunkSize = MaxChunkSize * 2)

      fortyMegabyteRequest ~> stubRoutes ~> check {
        status shouldBe BadRequest
      }
    }
  }
}
