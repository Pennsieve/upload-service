// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance

import akka.http.scaladsl.model.StatusCodes.{ NoContent, OK }
import akka.http.scaladsl.model.{ HttpRequest, Uri }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.implicits._
import com.blackfynn.service.utilities.ContextLogger
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.StubRoutes._
import com.blackfynn.upload.TestAuthentication._
import com.blackfynn.upload.TestData._
import com.blackfynn.upload.UploadPorts.{ CheckFileExists, GetPreview, ListParts }
import com.blackfynn.upload.model.{
  Eventual,
  FileMissingParts,
  FilesMissingParts,
  ImportId,
  PackagePreview
}
import com.blackfynn.upload.FakeS3Store
import io.circe.parser.decode
import org.scalatest.{ Matchers, WordSpec }

class StatusSpec extends WordSpec with Matchers with ScalatestRouteTest {
  implicit val log: ContextLogger = new ContextLogger()

  "A client should be able to retrieve the status of an upload" when {
    "not all chunks have been uploaded it" should {
      "receive status code OK" in {
        val store = new FakeS3Store
        statusRequest() ~> routes(store.listPartsEmpty) ~> check {
          status shouldBe OK
        }
      }

      "specify the parts that are missing" in {
        val store = new FakeS3Store
        statusRequest() ~> routes(store.listPartsEmpty) ~> check {
          val expectedFilesMissingParts =
            FilesMissingParts(List(FileMissingParts(someZipName, Set(0), 1))).asRight
              .map(_.files.map(_.toString))

          val missingFileStrings =
            decode[FilesMissingParts](entityAs[String]).map(_.files.map(_.toString))

          missingFileStrings shouldEqual expectedFilesMissingParts
        }
      }

      "handle plus sign" in {
        val store = new FakeS3Store

        statusRequest(anotherImportId) ~> routes(
          listParts = store.listPartsEmpty,
          packagePreview = plusPackagePreview
        ) ~> check {
          val expectedFilesMissingParts =
            FilesMissingParts(List(FileMissingParts(somePlusName, Set(0), 1))).asRight
              .map(_.files.map(_.toString))

          val missingFileStrings =
            decode[FilesMissingParts](entityAs[String]).map(_.files.map(_.toString))

          missingFileStrings shouldEqual expectedFilesMissingParts
        }
      }
    }

    "all chunks have been uploaded it" should {
      "receive status code NoContent" in {
        val store = new FakeS3Store
        statusRequest() ~> routes(store.listParts) ~> check {
          status shouldBe NoContent
        }
      }
    }

    "file already exists in S3 it" should {
      "receive status code NoContent" in {
        statusRequest() ~> routes(listParts404, checkFileExists = existsCheckFileExists) ~> check {
          status shouldBe NoContent
        }
      }
    }
  }

  def routes(
    listParts: ListParts,
    packagePreview: PackagePreview = zipPackagePreview,
    checkFileExists: CheckFileExists = nonexistentCheckFileExists
  ): Route =
    createRoutes(
      createPorts(
        listParts = listParts,
        getPreview = createGetPreview(packagePreview),
        checkFileExists = checkFileExists
      )
    )

  def createGetPreview(packagePreview: PackagePreview): GetPreview =
    _ => Eventual successful packagePreview

  def statusUri(importId: ImportId = defaultImportId): Uri =
    Uri(s"/status/organizations/$defaultOrganizationNodeId/id/$importId")
  def statusRequest(importId: ImportId = defaultImportId): HttpRequest =
    addJWT(HttpRequest(uri = statusUri(importId)))
}
