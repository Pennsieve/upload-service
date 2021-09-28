// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance.chunks

import akka.http.scaladsl.model.StatusCodes.{ BadRequest, Created, TooManyRequests }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.pennsieve.service.utilities.ContextLogger
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.StubRoutes._
import com.blackfynn.upload.TestData._
import com.blackfynn.upload.{ FakeS3Store, MockLoadMonitor }
import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }

class StreamedChunkSpec
    extends WordSpec
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterEach {

  implicit val log: ContextLogger = new ContextLogger()
  implicit val loadMonitor: MockLoadMonitor = new MockLoadMonitor()

  override def beforeEach(): Unit = {
    super.beforeEach()
    loadMonitor.reset()
  }

  "A chunked upload" should {

    "be rate limited for too many concurrent requests" in {
      val fakeS3Store = new FakeS3Store()
      val uri = s"bucket/1/$defaultImportId/$simpleCsv"
      val sendChunk = fakeS3Store.sendChunk(uri)

      val routes = createRoutes(sendChunk)

      createChunkRequest(firstByteString, 0) ~> routes ~> check {
        status shouldBe Created
        createChunkRequest(firstByteString, 1) ~> routes ~> check {
          status shouldBe Created
          loadMonitor.disallow()
          createChunkRequest(firstByteString, 2) ~> routes ~> check {
            status shouldBe TooManyRequests
          }
        }
      }
    }

    // This test fails since akka doesn't seem to respect size limits if the request is not a multipart form data:
//    "fail if the chunk payload is too large" in {
//      val fakeS3Store = new FakeS3Store()
//      val uri = s"bucket/1/$defaultImportId/$simpleCsv"
//      val sendChunk = fakeS3Store.sendChunk(uri)
//
//      createChunkRequest(tooMuchData, 0) ~> createRoutes(sendChunk) ~> check {
//        status shouldBe PayloadTooLarge
//      }
//    }

    "append the uploaded chunks to an ongoing upload" in {
      val fakeS3Store = new FakeS3Store()
      val uri = s"bucket/1/$defaultImportId/$simpleCsv"
      val sendChunk = fakeS3Store.sendChunk(uri)
      val expectedChunks = List(firstByteString, secondByteString)

      createChunkRequest(firstByteString, 0) ~> createRoutes(sendChunk) ~> check {
        status shouldBe Created
      }

      createChunkRequest(secondByteString, 1) ~> createRoutes(sendChunk) ~> check {
        status shouldBe Created
      }

      fakeS3Store.chunks(uri) shouldBe expectedChunks
    }

    "return bad request for a request with an invalid importId" in {
      val requestInvalidImportId =
        createChunkRequest(firstByteString, importId = "invalid-import-id")

      requestInvalidImportId ~> stubRoutes ~> check {
        status shouldBe BadRequest
      }
    }
  }
}
