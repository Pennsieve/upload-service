// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance

import java.util.UUID

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.StatusCodes.{ BadRequest, NotFound, OK }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ HttpOrigin, Origin }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import cats.implicits._
import com.amazonaws.services.s3.model.{ PartListing, PartSummary }
import com.pennsieve.auth.middleware.UserId
import com.pennsieve.models.NodeCodes.packageCode
import com.pennsieve.service.utilities.ContextLogger
import com.pennsieve.test.AwaitableImplicits
import com.blackfynn.upload.EntityImplicits.RichHttpEntity
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.StubRoutes._
import com.blackfynn.upload.TestAuthentication._
import com.blackfynn.upload.TestData._
import com.blackfynn.upload.model.FilesMissingParts._
import com.blackfynn.upload.model._
import com.blackfynn.upload.{ CompletePorts, FakeS3Store, HashPorts, UploadPorts }
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.parser.decode
import io.circe.syntax.EncoderOps
import org.scalatest.{ Matchers, WordSpec }

import scala.collection.convert.ImplicitConversionsToJava._
import scala.collection.immutable.Seq

class UploadCompleteSpec
    extends WordSpec
    with Matchers
    with ScalatestRouteTest
    with AwaitableImplicits {

  implicit val log: ContextLogger = new ContextLogger()

  "A upload complete endpoint" should {
    "close the multipart upload" in {
      val fakeS3Store = new FakeS3Store

      uploadCompleteRequest() ~> routes(fakeS3Store) ~> check {
        status shouldBe OK
      }

      fakeS3Store.markedComplete shouldBe true
    }

    "send files/upload/complete request to API" in {
      var sentCompleteRequest: Option[HttpRequest] = None
      val fileHashes =
        Map[String, FileHash](zipPackagePreview.files.head.fileName -> defaultFileHash)

      val savingSendComplete: CompletePorts.SendComplete =
        request =>
          Eventual.successful {
            sentCompleteRequest = Some(request)
            HttpResponse(entity = HttpEntity(someResponseJson))
          }

      val fakeS3Store: FakeS3Store = new FakeS3Store

      val request = uploadCompleteRequest(Some(allParameters))

      request ~> routes(fakeS3Store, savingSendComplete) ~> check {
        status shouldBe OK
      }

      sentCompleteRequest shouldBe someApiCompleteRequestAllParams(request.headers, fileHashes)
    }

    "send files/upload/complete request to API if a file already exists" in {
      var sentCompleteRequest: Option[HttpRequest] = None
      val fileHashes =
        Map[String, FileHash](zipPackagePreview.files.head.fileName -> defaultFileHash)

      val savingSendComplete: CompletePorts.SendComplete =
        request =>
          Eventual.successful {
            sentCompleteRequest = Some(request)
            HttpResponse(entity = HttpEntity(someResponseJson))
          }

      val fakeS3Store: FakeS3Store = new FakeS3Store

      val request = uploadCompleteRequest(Some(allParameters))

      val route =
        routes(fakeS3Store, savingSendComplete, existsCheckFileExists, Some(listParts404))

      request ~> route ~> check {
        status shouldBe OK
      }

      sentCompleteRequest shouldBe someApiCompleteRequestAllParams(request.headers, fileHashes)
    }

    "return NotFound if there are no parts in a upload" in {
      val expectedFilesMissingParts =
        FilesMissingParts(List(FileMissingParts(someZipName, Set(0), expectedTotalParts = 1))).files
          .map(_.toString)

      val fakeS3Store: FakeS3Store = new FakeS3Store

      val route = routes(fakeS3Store, maybeListParts = Some(emptyListParts))

      val request = uploadCompleteRequest(Some(allParameters))

      request ~> route ~> check {
        status shouldBe NotFound

        val filesMissingPartsStrings =
          decode[FilesMissingParts](response.entity.getString)
            .map(_.files.map(_.toString))

        filesMissingPartsStrings shouldBe Right(expectedFilesMissingParts)
      }
    }

    "remove origin headers from complete response" in {
      val fakeS3Store = new FakeS3Store

      uploadCompleteRequest() ~> routes(fakeS3Store) ~> check {
        status shouldBe OK
        response.headers should not contain origin
      }
    }

    "return the exact JSON sent from API" in {
      val fakeS3Store = new FakeS3Store

      val jsonResponseSendComplete: CompletePorts.SendComplete =
        _ =>
          Eventual.successful {
            HttpResponse(entity = HttpEntity(someResponseJson))
          }

      val request = uploadCompleteRequest(Some(allParameters))

      request ~> routes(fakeS3Store, jsonResponseSendComplete) ~> check {
        status shouldBe OK
        response.entity.getString shouldBe someResponseJson
      }
    }

    "accept a empty complete request from the client" in {
      val fakeS3Store = new FakeS3Store

      val request =
        uploadCompleteRequest(entity = HttpEntity.empty(ContentTypes.`application/json`))

      request ~> routes(fakeS3Store) ~> check {
        status shouldBe OK
      }
    }

    "set the destination id as the root of the ancestors of a package" in {
      val ancestor = CollectionUpload(CollectionId(), "root", None, 0)
      val parent = CollectionUpload(CollectionId(), "data", Some(ancestor.id), 1)
      val fileHashes =
        Map[String, FileHash](zipPackagePreview.files.head.fileName -> defaultFileHash)
      val previewWithParentAndAncestor =
        zipPackagePreview.copy(
          metadata =
            zipPackagePreview.metadata.copy(parent = Some(parent), ancestors = Some(List(ancestor)))
        )

      val someAncestorWithDestinationIdRoot =
        Some(List(ancestor.copy(parentId = Some(CollectionId(defaultDestinationId)))))

      val uploadCompleteWithInsertedDestination =
        previewToUploadCompleteRequest(
          preview = zipPackagePreview
            .copy(
              metadata = zipPackagePreview.metadata
                .copy(parent = Some(parent), ancestors = someAncestorWithDestinationIdRoot)
            ),
          fileHashes = fileHashes
        )

      var sentCompleteRequest: Option[HttpRequest] = None

      val savingSendComplete: CompletePorts.SendComplete =
        request =>
          Eventual.successful {
            sentCompleteRequest = Some(request)
            HttpResponse(entity = HttpEntity(someResponseJson))
          }

      val fakeS3Store = new FakeS3Store()

      uploadCompleteRequest(allParameters.some) ~> routes(
        fakeS3Store,
        savingSendComplete,
        getPreview = successfulGetPreview(previewWithParentAndAncestor)
      ) ~> check {
        status shouldBe OK
      }

      extractEntity(sentCompleteRequest) shouldBe Some(uploadCompleteWithInsertedDestination)
    }

    "set the destination id as the root of the parent of a package without ancestors" in {
      val parent = CollectionUpload(CollectionId(), "data", None, 0)
      val previewWithCollection =
        zipPackagePreview.copy(metadata = zipPackagePreview.metadata.copy(parent = Some(parent)))

      val someParentWithInsertedParent =
        Some(parent.copy(parentId = Some(CollectionId(defaultDestinationId))))

      val fileHashes =
        Map[String, FileHash](zipPackagePreview.files.head.fileName -> defaultFileHash)

      val uploadCompleteInsertedAncestorDestination =
        previewToUploadCompleteRequest(
          preview = zipPackagePreview
            .copy(
              metadata = zipPackagePreview.metadata.copy(parent = someParentWithInsertedParent)
            ),
          fileHashes = fileHashes
        )

      var sentCompleteRequest: Option[HttpRequest] = None

      val savingSendComplete: CompletePorts.SendComplete =
        request =>
          Eventual.successful {
            sentCompleteRequest = Some(request)
            HttpResponse(entity = HttpEntity(someResponseJson))
          }

      val fakeS3Store = new FakeS3Store()

      uploadCompleteRequest(allParameters.some) ~> routes(
        fakeS3Store,
        savingSendComplete,
        getPreview = successfulGetPreview(previewWithCollection)
      ) ~> check {
        status shouldBe OK
      }

      extractEntity(sentCompleteRequest) shouldBe Some(uploadCompleteInsertedAncestorDestination)
    }

    "not set the destination id if there is no parent or ancestors" in {
      var sentCompleteRequest: Option[HttpRequest] = None

      val savingSendComplete: CompletePorts.SendComplete =
        request =>
          Eventual.successful {
            sentCompleteRequest = Some(request)
            HttpResponse(entity = HttpEntity(someResponseJson))
          }

      val fakeS3Store = new FakeS3Store()

      uploadCompleteRequest(allParameters.some) ~> routes(fakeS3Store, savingSendComplete) ~> check {
        status shouldBe OK
      }

      val maybeUploadCompleteRequest = extractEntity(sentCompleteRequest)

      maybeUploadCompleteRequest.flatMap(_.preview.parent) shouldBe None
      maybeUploadCompleteRequest.flatMap(_.preview.ancestors) shouldBe None
    }

    "check for an ongoing multipart upload before checking if file already exists" in {
      val expectedFilesMissingParts =
        FilesMissingParts(List(FileMissingParts(someZipName, Set(0), expectedTotalParts = 1))).files
          .map(_.toString)

      val fakeS3Store: FakeS3Store = new FakeS3Store

      val route = routes(
        fakeS3Store,
        maybeListParts = Some(emptyListParts),
        checkFileExists = notImplementedCheckFileExists
      )

      uploadCompleteRequest(Some(allParameters)) ~> route ~> check {
        status shouldBe NotFound

        val filesMissingPartsStrings =
          decode[FilesMissingParts](response.entity.getString)
            .map(_.files.map(_.toString))

        filesMissingPartsStrings shouldBe Right(expectedFilesMissingParts)
      }
    }

    "accept a package id as a destination id" in {
      val fakeS3Store = new FakeS3Store

      val completeRequestWithPackageDestination =
        uploadCompleteRequest(
          Some(s"&append=true&destinationId=N:$packageCode:${UUID.randomUUID()}")
        )

      completeRequestWithPackageDestination ~> routes(fakeS3Store) ~> check {
        status shouldBe OK
      }

      fakeS3Store.markedComplete shouldBe true
    }

    "not accept a package id as a destination id if append is false" in {
      val fakeS3Store = new FakeS3Store

      val appendMustBeTrueForFilePackage =
        "Invalid destinationId: Set append to true to insert in to package destination"

      val completeRequestWithPackageDestination =
        uploadCompleteRequest(Some(s"&destinationId=N:$packageCode:${UUID.randomUUID()}"))

      completeRequestWithPackageDestination ~> routes(fakeS3Store) ~> check {
        status shouldBe BadRequest

        response.entity.getString shouldBe appendMustBeTrueForFilePackage
      }
    }

    "not accept a collection id as a destination if the append is true" in {
      val fakeS3Store = new FakeS3Store
      val appendMustBeFalseForCollection =
        "Invalid destinationId: Append must be false for uploading to a collection"

      val completeRequestWithPackageDestination =
        uploadCompleteRequest(Some(s"&append=true&destinationId=$defaultDestinationId"))

      completeRequestWithPackageDestination ~> routes(fakeS3Store) ~> check {
        status shouldBe BadRequest

        response.entity.getString shouldBe appendMustBeFalseForCollection
      }
    }
  }

  private def extractEntity(sentCompleteRequest: Option[HttpRequest]) =
    sentCompleteRequest
      .flatMap { request =>
        decode[UploadCompleteRequest](request.entity.getString).toOption
      }

  val stubProxyLink = ProxyLink("id", "other-id", List.empty)

  private def previewToUploadCompleteRequest(
    preview: PackagePreview,
    fileHashes: Map[String, FileHash]
  ) = {
    UploadCompleteRequest(preview.toApiPreview(fileHashes), proxyLink = Some(stubProxyLink))
  }

  val previewUri: UploadPreviewUri = UploadPreviewUri(UserId(defaultUserId), defaultImportId)
  val previewKey: String = s"$previewUri$defaultKeyId"
  val previews: Map[String, PackagePreview] = Map(previewKey -> zipPackagePreview)

  private def uploadCompleteRequest(
    otherParams: Option[String] = None,
    entity: RequestEntity = HttpEntity(stubProxyLink.asJson.noSpaces)
  ) =
    createPost(
      makeToken(),
      entity,
      s"/complete/organizations/node-id/id/$defaultImportId?datasetId=dataset-id${otherParams.getOrElse("")}"
    )

  private def someApiCompleteRequestAllParams(
    headers: Seq[HttpHeader],
    fileHashes: Map[String, FileHash]
  ) = {
    val entity =
      Marshal(
        UploadCompleteRequest(zipPackagePreview.toApiPreview(fileHashes), Some(stubProxyLink)).asJson
      ).to[RequestEntity]
        .awaitFinite()

    Some {
      HttpRequest(
        POST,
        Uri(
          s"https://localhost/files/upload/complete/$defaultImportId?datasetId=dataset-id$allParameters"
        ),
        headers = headers,
        entity = entity
      )
    }
  }
  val someResponseJson = """{"some":"json"}"""
  val allParameters =
    s"&append=false&destinationId=$defaultDestinationId&uploadService=true&hasPreview=true"

  private val origin = Origin(HttpOrigin("https://api.blackfynn.io"))

  private val successfulSendComplete: CompletePorts.SendComplete =
    _ =>
      Eventual successful {
        HttpResponse(headers = Seq(origin), entity = HttpEntity(someResponseJson))
      }

  private val doesntExistCheckFileExists: UploadPorts.CheckFileExists =
    _ => Eventual successful false

  private val emptyListParts: UploadPorts.ListParts =
    _ =>
      Eventual successful {
        val listing = new PartListing()
        listing.setParts(List.empty[PartSummary])
        Option(listing)
      }

  private def successfulGetPreview(preview: PackagePreview): UploadPorts.GetPreview = { _ =>
    {
      Eventual.successful(preview)
    }
  }

  private def successfulGetChunkHashes(preview: PackagePreview): HashPorts.GetChunkHashes =
    (hashIds, importId) => {
      val chunkHash = ChunkHash(
        ChunkHashId(
          UploadUri(UserId(defaultUserId), preview.metadata.importId, preview.files.head.fileName),
          0
        ),
        preview.metadata.importId,
        defaultFileHash.hash
      )

      Source(List(Right(chunkHash): Either[Throwable, ChunkHash]))
    }

  private def routes(
    fakeS3Store: FakeS3Store,
    sendComplete: CompletePorts.SendComplete = successfulSendComplete,
    checkFileExists: UploadPorts.CheckFileExists = doesntExistCheckFileExists,
    maybeListParts: Option[UploadPorts.ListParts] = None,
    getPreview: UploadPorts.GetPreview = successfulGetPreview(zipPackagePreview),
    getChunkHashes: HashPorts.GetChunkHashes = successfulGetChunkHashes(zipPackagePreview)
  ): Route = {
    val ports = createPorts(
      listParts = maybeListParts getOrElse fakeS3Store.listParts,
      getPreview = getPreview,
      hashGetPreview = getPreview,
      completeUpload = fakeS3Store.completeUpload,
      sendComplete = sendComplete,
      checkFileExists = checkFileExists,
      getChunkHashes = getChunkHashes
    )

    createRoutes(ports)
  }
}
