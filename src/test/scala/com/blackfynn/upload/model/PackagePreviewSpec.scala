// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import java.util.UUID

import com.pennsieve.models.{ FileType, PackageType }
import com.blackfynn.upload.TestData.defaultFileHash
import io.circe.parser._
import io.circe.syntax._
import org.scalatest.{ Matchers, WordSpec }

class PackagePreviewSpec extends WordSpec with Matchers {
  "PackagePreview" should {
    val importId = ImportId(UUID.randomUUID())
    val preview = PackagePreview(
      metadata = PackagePreviewMetadata(
        packageName = "test+package",
        escapedPackageName = "test%2Bpackage",
        packageType = PackageType.Unsupported,
        packageSubtype = "none",
        fileType = FileType.CSV,
        warnings = List.empty,
        groupSize = 0,
        hasWorkflow = false,
        importId = importId,
        icon = "icon.png",
        parent = None,
        ancestors = None,
        previewPath = None,
        escapedPreviewPath = None
      ),
      files = List(
        PreviewFile(
          uploadId = 0,
          fileName = "file+name",
          escapedFileName = "file%2Bname",
          size = 10,
          multipartUploadId = Some(MultipartUploadId("multi-part-id")),
          chunkedUpload = None
        )
      )
    )

    val expectedJsonWithoutHash = s"""{
      |  "files" : [
      |    {
      |      "uploadId" : 0,
      |      "fileName" : "file+name",
      |      "escapedFileName" : "file%2Bname",
      |      "size" : 10,
      |      "multipartUploadId" : "multi-part-id",
      |      "chunkedUpload" : null
      |    }
      |  ],
      |  "packageName" : "test+package",
      |  "escapedPackageName" : "test%2Bpackage",
      |  "packageType" : "Unsupported",
      |  "packageSubtype" : "none",
      |  "fileType" : "CSV",
      |  "warnings" : [
      |  ],
      |  "groupSize" : 0,
      |  "hasWorkflow" : false,
      |  "importId" : "$importId",
      |  "icon" : "icon.png",
      |  "parent" : null,
      |  "ancestors" : null,
      |  "previewPath" : null,
      |  "escapedPreviewPath" : null
      |}""".stripMargin

    val expectedJsonWithHash = s"""{
      |  "files" : [
      |    {
      |      "uploadId" : 0,
      |      "fileName" : "file+name",
      |      "escapedFileName" : "file%2Bname",
      |      "size" : 10,
      |      "fileHash": { "hash": "${defaultFileHash.hash}" },
      |      "multipartUploadId" : "multi-part-id",
      |      "chunkedUpload" : null
      |    }
      |  ],
      |  "packageName" : "test+package",
      |  "escapedPackageName" : "test%2Bpackage",
      |  "packageType" : "Unsupported",
      |  "packageSubtype" : "none",
      |  "fileType" : "CSV",
      |  "warnings" : [
      |  ],
      |  "groupSize" : 0,
      |  "hasWorkflow" : false,
      |  "importId" : "$importId",
      |  "icon" : "icon.png",
      |  "parent" : null,
      |  "ancestors" : null,
      |  "previewPath" : null,
      |  "escapedPreviewPath" : null
      |}""".stripMargin

    val expectedJsonWithBareHash = s"""{
      |  "files" : [
      |    {
      |      "uploadId" : 0,
      |      "fileName" : "file+name",
      |      "escapedFileName" : "file%2Bname",
      |      "size" : 10,
      |      "fileHash": "${defaultFileHash.hash}",
      |      "multipartUploadId" : "multi-part-id",
      |      "chunkedUpload" : null
      |    }
      |  ],
      |  "packageName" : "test+package",
      |  "escapedPackageName" : "test%2Bpackage",
      |  "packageType" : "Unsupported",
      |  "packageSubtype" : "none",
      |  "fileType" : "CSV",
      |  "warnings" : [
      |  ],
      |  "groupSize" : 0,
      |  "hasWorkflow" : false,
      |  "importId" : "$importId",
      |  "icon" : "icon.png",
      |  "parent" : null,
      |  "ancestors" : null,
      |  "previewPath" : null,
      |  "escapedPreviewPath" : null
      |}""".stripMargin

    "encode to expected json" in {
      preview.asJson.spaces2 shouldBe expectedJsonWithoutHash
    }

    "decode from expected json" in {
      decode[PackagePreview](expectedJsonWithoutHash).right.get shouldBe preview
    }

    "decode api preview from expected json" in {
      val fileHashes =
        Map[String, FileHash]("file+name" -> defaultFileHash)

      val expectedApiPackagePreview = preview.toApiPreview(fileHashes)

      decode[ApiPackagePreview](expectedJsonWithHash).right.get shouldBe expectedApiPackagePreview
      decode[ApiPackagePreview](expectedJsonWithBareHash).right.get shouldBe expectedApiPackagePreview
    }
  }
}
