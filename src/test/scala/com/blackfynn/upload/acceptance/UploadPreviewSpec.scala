// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance

import akka.http.scaladsl.model.StatusCodes.{ BadRequest, Created, Locked, Unauthorized }
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest }
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.auth.middleware.DatasetId
import com.pennsieve.models.FileType.ZIP
import com.pennsieve.models.{ FileType, PackageType, Role }
import com.pennsieve.service.utilities.ContextLogger
import com.pennsieve.test.AwaitableImplicits
import com.blackfynn.upload.PreviewPorts.{ CachePreview, InitiateUpload }
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.TestAuthentication._
import com.blackfynn.upload.TestData._
import com.blackfynn.upload.model._
import com.blackfynn.upload.routing.UploadRouting
import com.blackfynn.upload.{ FakeS3Store, LoadMonitor, MockLoadMonitor }
import io.circe.parser.decode
import io.circe.syntax.EncoderOps
import org.scalatest.{ Matchers, WordSpecLike }

import scala.collection.mutable
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ Await, Future }

class UploadPreviewSpec
    extends WordSpecLike
    with Matchers
    with ScalatestRouteTest
    with AwaitableImplicits {

  implicit val log: ContextLogger = new ContextLogger()
  implicit val loadMonitor: LoadMonitor = new MockLoadMonitor()

  private def cleanS3Key(key: String): String =
    key.replaceAll("[^a-zA-Z0-9./@-]", "_")

  "POST upload/preview/organizations/{organizationId}" should {

    "create an upload preview" in {
      val expectedFile = PreviewFile(1, someZipName, 100, multipartId, chunkedUpload)

      makeRequest() ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 1

        val packagePreview = packages.head

        packagePreview.files.head shouldBe expectedFile
        packagePreview.metadata.hasWorkflow shouldBe true
        packagePreview.metadata.fileType shouldBe ZIP
        packagePreview.metadata.packageName shouldBe "some^.zip"
        packagePreview.metadata.escapedPackageName shouldBe "some_.zip"
        packagePreview.metadata.packageType shouldBe PackageType.ZIP
        packagePreview.metadata.packageSubtype shouldBe "Compressed"
        packagePreview.metadata.groupSize shouldBe 100L
        packagePreview.metadata.warnings shouldBe Vector.empty[String]
        packagePreview.metadata.icon shouldBe "Zip"
      }
    }

    "combine associated files into a package" in {
      val combineRequest =
        PreviewRequest(
          List(
            // brain should be kept separate and result in a package called "brain.png"
            UserFile(1, "brain.png", 243556, defaultFileHash.some, defaultChunkSize.some, None),
            // combine these into a single persyst package named "test"
            UserFile(1, "test.lay", 243556, defaultFileHash.some, defaultChunkSize.some, None),
            UserFile(2, "test.dat", 2556456, defaultFileHash.some, defaultChunkSize.some, None)
          )
        )
      val request: HttpRequest = makeRequest(combineRequest.asJson.noSpaces)
      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages
        packages.length shouldBe 2
        packages.find(p => p.metadata.packageName == "brain.png").isDefined shouldBe (true)
        packages.find(p => p.metadata.packageName == "test").isDefined shouldBe (true)
      }
    }

    "create an upload preview for a variety of file types" in {
      val manyFileTypes =
        PreviewRequest(
          List(
            UserFile(1, "TestMEF.mef", 444452, defaultFileHash.some, defaultChunkSize.some),
            UserFile(4, "TestNEV.ns1", 4566456, defaultFileHash.some, defaultChunkSize.some),
            UserFile(5, "TestIMG.img", 243556, defaultFileHash.some, defaultChunkSize.some),
            UserFile(6, "TestIMG.hdr", 2556456, defaultFileHash.some, defaultChunkSize.some),
            UserFile(7, "TestLAY.lay", 35353, defaultFileHash.some, defaultChunkSize.some),
            UserFile(8, "TestDAT.dat", 234554, defaultFileHash.some, defaultChunkSize.some),
            UserFile(9, "TestPDF.pdf", 34535, defaultFileHash.some, defaultChunkSize.some),
            UserFile(10, "TestSpikes.spikes", 35676, defaultFileHash.some, defaultChunkSize.some),
            UserFile(11, "TestEvents.events", 74567, defaultFileHash.some, defaultChunkSize.some),
            UserFile(12, "TestNEX1.nex", 5677, defaultFileHash.some, defaultChunkSize.some),
            UserFile(13, "TestNEX2.nex", 35467, defaultFileHash.some, defaultChunkSize.some),
            UserFile(14, "TestNEX3.nex", 567567, defaultFileHash.some, defaultChunkSize.some),
            UserFile(15, "TestCSV.csv", 567546, defaultFileHash.some, defaultChunkSize.some),
            UserFile(16, "TestOTHER.bfannot", 300, defaultFileHash.some, defaultChunkSize.some),
            UserFile(17, "TestMoberg.moberg.gz", 2302, defaultFileHash.some, defaultChunkSize.some),
            UserFile(18, "TestPersyst.lay", 35353, defaultFileHash.some, defaultChunkSize.some),
            UserFile(19, "TestPersyst.dat", 234554, defaultFileHash.some, defaultChunkSize.some)
          )
        )

      val request: HttpRequest = makeRequest(manyFileTypes.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        /*
          Expected packages (12):
          TestMEF.mef
          TestNEV.ns1
          TestIMG.img + TestIMG.hdr
          TestLAY.lay
          TestDAT.dat
          TestPDF.pdf
          TestSpikes.spikes + TestEvents.events
          TestNEX1.nex + TestNEX2.nex + TestNEX3.nex
          TestCSV.csv
          TestOTHER.bfannot
          TestMoberg.moberg.gz
          TestPersyst.lay + TestPersyst.dat
         */

        packages.length shouldBe 12
      }
    }

    "upload preview should handle the plus sign correctly on filenames" in {
      val manyFileTypes =
        PreviewRequest(
          List(UserFile(1, "Test+MEF.mef", 444452, defaultFileHash.some, defaultChunkSize.some))
        )

      val request: HttpRequest = makeRequest(manyFileTypes.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 1
        packages.head.metadata.packageName shouldBe "Test+MEF.mef"
        packages.head.metadata.escapedPackageName shouldBe "Test_MEF.mef"
        packages.head.files.length shouldBe 1
        packages.head.files.head.fileName shouldBe "Test+MEF.mef"
        packages.head.files.head.escapedFileName shouldBe "Test_MEF.mef"

      }
    }

    "upload preview should handle naming conventions on filenames" in {
      val manyFileTypes =
        PreviewRequest(
          List(UserFile(1, "Test$MEF.mef", 444452, defaultFileHash.some, defaultChunkSize.some))
        )

      val request: HttpRequest = makeRequest(manyFileTypes.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 1
        packages.head.metadata.packageName shouldBe "Test$MEF.mef"
        packages.head.metadata.escapedPackageName shouldBe "Test_MEF.mef"
        packages.head.files.length shouldBe 1
        packages.head.files.head.fileName shouldBe "Test$MEF.mef"
        packages.head.files.head.escapedFileName shouldBe "Test_MEF.mef"
      }
    }

    "create an upload preview for collections" in {
      val manyFileTypes =
        PreviewRequest(
          List(
            UserFile(
              1,
              "sub1sam1.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-1", "sam-1", "microscopy"))
            ),
            UserFile(
              2,
              "sub1sam1.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-1", "sam-1", "microscopy"))
            ),
            UserFile(
              3,
              "sub1sam2.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-1", "sam-2", "microscopy"))
            ),
            UserFile(
              4,
              "sub1sam2.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-1", "sam-2", "microscopy"))
            ),
            UserFile(
              5,
              "sub2sam1.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-2", "sam-1", "microscopy"))
            ),
            UserFile(
              6,
              "sub2sam1.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-2", "sam-1", "microscopy"))
            ),
            UserFile(
              7,
              "sub2sam2.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-2", "sam-2", "microscopy"))
            ),
            UserFile(
              8,
              "sub2sam2.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-2", "sam-2", "microscopy"))
            ),
            UserFile(
              9,
              "sub3sam1.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-3", "sam-1", "microscopy"))
            ),
            UserFile(
              10,
              "sub3sam1.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-3", "sam-1", "microscopy"))
            ),
            UserFile(
              11,
              "sub3sam2.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-3", "sam-2", "microscopy"))
            ),
            UserFile(
              12,
              "sub3sam2.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "sub-3", "sam-2", "microscopy"))
            )
          )
        )

      val request: HttpRequest = makeRequest(manyFileTypes.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages
        packages.length shouldBe 6

        val testImgPackage = packages.find(p => p.metadata.packageName == "sub2sam1").get
        testImgPackage.metadata.ancestors.get.map(_.name) shouldBe List("data", "sub-2", "sam-1")
        testImgPackage.metadata.parent.get.name shouldBe "microscopy"
        testImgPackage.metadata.previewPath shouldBe Some(
          FilePath("data", "sub-2", "sam-1", "microscopy")
        )
        val testImg1Package = packages.find(p => p.metadata.packageName == "sub3sam2").get
        testImg1Package.metadata.ancestors.get
          .map(_.name) shouldBe List("data", "sub-3", "sam-2")
        testImg1Package.metadata.parent.get.name shouldBe "microscopy"
        testImg1Package.metadata.previewPath shouldBe Some(
          FilePath("data", "sub-3", "sam-2", "microscopy")
        )
      }
    }

    "upload preview for collections respects naming conventions" in {
      val manyFileTypes =
        PreviewRequest(
          List(
            UserFile(
              1,
              "sub1sam1.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath(List("dat^a", "sub-1", "sa%m-1", "micros+copy"), false))
              //to accurately represents what is typically received by the endpoint, we need to bypass the name escaping
              // in the filePath creation to allow bad characters in the request, hence the list of strings and boolean
            ),
            UserFile(
              2,
              "sub1sam1.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath(List("dat^a", "sub-1", "sa%m-1", "micros+copy"), false))
            )
          )
        )

      val request: HttpRequest = makeRequest(manyFileTypes.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages
        packages.length shouldBe 1

        val testImgPackage = packages.find(p => p.metadata.packageName == "sub1sam1").get
        testImgPackage.metadata.ancestors.get
          .map(_.name) shouldBe List("dat^a", "sub-1", "sa%m-1")
        testImgPackage.metadata.parent.get.name shouldBe "micros+copy"
        testImgPackage.metadata.previewPath shouldBe Some(
          FilePath("dat^a", "sub-1", "sa%m-1", "micros+copy")
        )

        testImgPackage.metadata.escapedPreviewPath shouldBe Some(
          FilePath("dat_a", "sub-1", "sa_m-1", "micros_copy")
        )
      }
    }

    "create an upload preview for a paired file and annotation" in {
      val annotationWithFile =
        PreviewRequest(
          List(
            UserFile(2, "TestEDF.edf", 4546542, defaultFileHash.some, defaultChunkSize.some),
            UserFile(3, "TestEDF.bfannot", 2345456, defaultFileHash.some, defaultChunkSize.some)
          )
        )
      val request = makeRequest(annotationWithFile.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 1
      }
    }

    "create an upload preview for a collection of annotations" in {
      val annotations =
        PreviewRequest(
          List(
            UserFile(1, "Annotation1.bfannot", 577, defaultFileHash.some, defaultChunkSize.some),
            UserFile(2, "Annotation2.bfannot", 4657, defaultFileHash.some, defaultChunkSize.some)
          )
        )

      val request = makeRequest(annotations.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 2
      }
    }

    "create an upload preview for a collection of generic files" in {
      val genericFiles =
        PreviewRequest(
          List(
            UserFile(1, "Some.file.name.png", 1000, defaultFileHash.some, defaultChunkSize.some),
            UserFile(2, "Another.file.png", 2000, defaultFileHash.some, defaultChunkSize.some),
            UserFile(3, "Generic.blah", 3000, defaultFileHash.some, defaultChunkSize.some)
          )
        )

      val request = makeRequest(genericFiles.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 3
      }
    }

    "create an upload preview for a package missing a master file" in {
      val datFile =
        PreviewRequest(
          List(UserFile(1, "TestDAT.dat", 1000, defaultFileHash.some, defaultChunkSize.some))
        )

      val request = makeRequest(datFile.asJson.noSpaces)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 1
        packages.head.metadata.fileType shouldBe FileType.Data
      }
    }

    "create a single upload preview for appended annotations" in {
      val annotations =
        PreviewRequest(
          List(
            UserFile(1, "Annotation1.bfannot", 577, defaultFileHash.some, defaultChunkSize.some),
            UserFile(2, "Annotation2.bfannot", 4657, defaultFileHash.some, defaultChunkSize.some)
          )
        )

      val request = makeRequest(annotations.asJson.noSpaces, append = true)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 1
      }
    }

    "cache each created preview by upload directory" in {
      val cachedMetadata = mutable.Map[ImportId, PackagePreviewMetadata]()
      val cachedFiles = mutable.Map[ImportId, List[PreviewFile]]()
      makeRequest() ~> route(cachePreview(cachedMetadata, cachedFiles)) ~> check {
        status shouldBe Created

        cachedMetadata.size shouldBe 1
        cachedMetadata.values.head.packageName shouldBe "some^.zip"
        cachedMetadata.values.head.escapedPackageName shouldBe "some_.zip"

        cachedFiles.size shouldBe 1
        cachedFiles.values.head.size shouldBe 1
        cachedFiles.values.head.head.fileName shouldBe "some^.zip"
        cachedFiles.values.head.head.escapedFileName shouldBe "some_.zip"

      }
    }

    "start a multipart upload for a file in a upload" in {
      val fakeS3Store = new FakeS3Store()

      makeRequest() ~> route(initiateUpload = fakeS3Store.initiateMultipartUpload) ~> check {
        Await.result(responseEntity.toStrict(1 second), 1 second)

        status shouldBe Created

        fakeS3Store.storedUploadInitiations.keys.size shouldBe 1
      }
    }

    "accept a file with size 0" in {
      val expectedFile = PreviewFile(1, "some.zip", 0L, multipartId, chunkedUpload)

      val previewSizeZeroRequestJson =
        PreviewRequest(
          List(UserFile(1, "some.zip", 0L, defaultFileHash.some, defaultChunkSize.some))
        ).asJson.noSpaces

      val request: HttpRequest = makeRequest(previewSizeZeroRequestJson)

      request ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.length shouldBe 1

        val packagePreview = packages.head

        packagePreview.files.head shouldBe expectedFile
      }
    }

    "return unauthorized for a JWT decoding failure" in {
      val req: HttpRequest = makeRequest(maybeDatasetId = None)
        .removeHeader("Authorization")
        .addHeader(Authorization(OAuth2BearerToken("foo")))
      req ~> route() ~> check {
        status shouldBe Unauthorized
      }
    }

    "return unauthorized for a user without permissions for a dataset" in {

      val tokenForDefaultDataset =
        makeToken(createClaim = generateClaim(), maybeDatasetId = Some(DatasetId(defaultDatasetId)))

      val urlWithDifferentDatasetId =
        s"/preview/organizations/$defaultOrganizationNodeId?append=false&dataset_id=5"

      val request =
        createPost(tokenForDefaultDataset, HttpEntity(zipFile), urlWithDifferentDatasetId)

      request ~> route() ~> check {
        status shouldBe Unauthorized
      }
    }

    "return unauthorized for a user without Editor Role for a dataset" in {
      makeRequest(createClaim = generateClaim(Role.Viewer)) ~> route() ~> check {
        status shouldBe Unauthorized
      }
    }

    "return locked for a dataset that is locked" in {
      makeRequest(createClaim = generateClaim(datasetLocked = Some(true))) ~> route() ~> check {
        status shouldBe Locked
      }
    }

    "return created for a dataset that is not locked" in {
      makeRequest(createClaim = generateClaim(datasetLocked = Some(false))) ~> route() ~> check {
        status shouldBe Created
      }
    }

    "return created for a dataset whose locked status is unknown" in {
      makeRequest(createClaim = generateClaim()) ~> route() ~> check {
        status shouldBe Created
      }
    }

    "return created for a wildcard dataset claim, even if it has a locked field of true" in {
      makeRequest(createClaim = generateClaim(wildcardDataset = true, datasetLocked = Some(true))) ~> route() ~> check {
        status shouldBe Created
      }
    }

    "set the root ancestors parent to be the destination parameter" in {
      val imageFolder =
        PreviewRequest(
          List(
            UserFile(
              1,
              "TestIMG.img",
              243556,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "images"))
            ),
            UserFile(
              2,
              "TestIMG.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("data", "images"))
            )
          )
        ).asJson.noSpaces

      makeRequest(imageFolder, maybeDestination = Some(defaultDestinationId)) ~> route() ~> check {
        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages

        packages.head.metadata.ancestors.get.head.parentId shouldBe Some(
          CollectionId(defaultDestinationId)
        )
      }
    }

    "set a packages parent's parent to be the sent destinationId" in {
      val imageFolder =
        PreviewRequest(
          List(
            UserFile(
              1,
              "TestIMG.hdr",
              2556456,
              defaultFileHash.some,
              defaultChunkSize.some,
              Some(FilePath("images"))
            )
          )
        ).asJson.noSpaces

      makeRequest(imageFolder, maybeDestination = Some(defaultDestinationId)) ~> route() ~> check {
        val json = entityAs[String]

        val packages = decode[PreviewPackageResponse](json).right.get.packages

        packages.head.metadata.parent.flatMap(_.parentId) shouldBe Some(
          CollectionId(defaultDestinationId)
        )
      }
    }

    "return created for a request without a dataset id" in {
      makeRequest(maybeDatasetId = None) ~> route() ~> check {
        status shouldBe Created
      }
    }

    "return bad request for a request with an invalid destinationId" in {
      makeRequest(maybeDestination = Some("invalid-format")) ~> route() ~> check {
        status shouldBe BadRequest
      }
    }

    "return a string file path if a string file path was sent" in {
      val fileWithStringFilePath =
        s"""
        |{
        |   "files": [
        |       {
        |         "uploadId":1,
        |         "fileName":"TestIMG.hdr",
        |         "escapedFileName": "${cleanS3Key("TestIMG.hdr")}",
        |         "size":200,
        |         "fileHash": { "hash": "${defaultFileHash.hash}" },
        |         "filePath":"data/images"
        |       }
        |   ]
        |}
      """.stripMargin

      makeRequest(fileWithStringFilePath) ~> route() ~> check {
        status shouldBe Created
        entityAs[String] should include("data/images")
      }
    }

    "construct an escaped file name if it is not provided in the request" in {
      val fileWithStringFilePath =
        s"""
           |{
           |   "files": [
           |       {
           |         "uploadId":1,
           |         "fileName":"Test+MEF.mef",
           |         "size":200,
           |         "fileHash": { "hash": "${defaultFileHash.hash}" }
           |       }
           |   ]
           |}
        """.stripMargin

      makeRequest(fileWithStringFilePath) ~> route() ~> check {
        status shouldBe Created

        val packages = decode[PreviewPackageResponse](entityAs[String]).right.get.packages
        packages.length shouldBe 1
        packages.head.metadata.packageName shouldBe "Test+MEF.mef"
        packages.head.metadata.escapedPackageName shouldBe "Test_MEF.mef"
        packages.head.files.length shouldBe 1
        packages.head.files.head.fileName shouldBe "Test+MEF.mef"
        packages.head.files.head.escapedFileName shouldBe "Test_MEF.mef"
      }
    }

    "return a list file path if a list file path was sent" in {
      val fileWithStringFilePath =
        s"""
          |{
          |   "files": [
          |       {
          |         "uploadId":1,
          |         "fileName":"TestIMG.hdr",
          |         "escapedFileName": "${cleanS3Key("TestIMG.hdr")}",
          |         "size":200,
          |         "fileHash": { "hash": "${defaultFileHash.hash}" },
          |         "filePath":["data", "images"]
          |       }
          |   ]
          |}
        """.stripMargin

      makeRequest(fileWithStringFilePath) ~> route() ~> check {
        status shouldBe Created
        entityAs[String] should include("""["data","images"]""")
      }
    }
  }

  val chunkedUpload = ChunkedUpload(5242880L, 1)

  val multipartId = "9d898281-642c-4ed5-af17-262dd3894b5a"

  val someRootPath: Option[FilePath] = Some(FilePath("/"))
  val zipFile: String =
    PreviewRequest(
      List(
        UserFile(1, someZipName, 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath)
      )
    ).asJson.noSpaces
  val largeDicomPackage: String =
    PreviewRequest(
      List(
        UserFile(1, "1.dcm", 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath),
        UserFile(2, "2.dcm", 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath),
        UserFile(3, "3.dcm", 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath),
        UserFile(4, "4.dcm", 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath),
        UserFile(5, "5.dcm", 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath),
        UserFile(6, "6.dcm", 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath),
        UserFile(7, "7.dcm", 100L, defaultFileHash.some, defaultChunkSize.some, someRootPath)
      )
    ).asJson.noSpaces

  def cachePreview(
    cachedMetadata: mutable.Map[ImportId, PackagePreviewMetadata],
    cachedFiles: mutable.Map[ImportId, List[PreviewFile]]
  ): CachePreview =
    (preview: PackagePreview) => {
      cachedMetadata += preview.metadata.importId -> preview.metadata
      cachedFiles += preview.metadata.importId -> preview.files
      EitherT.pure[Future, Throwable](())
    }

  def route(
    cachePreview: CachePreview = doNothingCache,
    initiateUpload: InitiateUpload = successfulInitiateUpload
  ): Route =
    Route.seal(
      UploadRouting(
        stubUploadConfig,
        createPorts(initiateUpload = initiateUpload, cachePreview = cachePreview)
      )
    )

  private def makeRequest(
    body: String = zipFile,
    append: Boolean = false,
    maybeDatasetId: Option[DatasetId] = Some(DatasetId(defaultDatasetId)),
    createClaim: CreateClaim = generateClaim(),
    maybeDestination: Option[String] = None
  ): HttpRequest = {
    val destination = maybeDestination.fold("")(dest => s"&destinationId=$dest")
    val datasetId = maybeDatasetId.fold("")(dataset => s"&dataset_id=${dataset.value}")

    createPost(
      makeToken(createClaim = createClaim, maybeDatasetId = maybeDatasetId),
      HttpEntity(body),
      s"/preview/organizations/$defaultOrganizationNodeId?append=$append$datasetId$destination"
    )
  }
}
