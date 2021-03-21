// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import java.security.MessageDigest
import java.util.UUID

import akka.stream.alpakka.s3.auth.encodeHex
import akka.util.ByteString
import cats.data.NonEmptyList
import com.blackfynn.models.Utilities._
import com.blackfynn.models.{ FileType, FileTypeGrouping, FileTypeInfo, PackageType }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax._
import io.circe.{ Decoder, Encoder, HCursor, Json }

import scala.math.{ ceil, max }

final case class PackagePreview(metadata: PackagePreviewMetadata, files: List[PreviewFile]) {
  def toApiPreview(fileHashes: Map[String, FileHash]): ApiPackagePreview =
    ApiPackagePreview(
      metadata.packageName,
      metadata.escapedPackageName,
      metadata.packageType,
      metadata.packageSubtype,
      metadata.fileType,
      files.map(
        f =>
          f.toUserFile(
            fileHashes
              .get(f.fileName)
              .getOrElse(throw new Throwable(s"Missing hash for ${f.fileName}"))
          )
      ),
      metadata.warnings,
      metadata.groupSize,
      metadata.hasWorkflow,
      metadata.importId,
      metadata.icon,
      metadata.parent,
      metadata.ancestors
    )
}

final case class PackagePreviewMetadata(
  packageName: String,
  escapedPackageName: String,
  packageType: PackageType,
  packageSubtype: String,
  fileType: FileType,
  warnings: Iterable[String],
  groupSize: Long,
  hasWorkflow: Boolean,
  importId: ImportId,
  icon: String,
  parent: Option[CollectionUpload],
  ancestors: Option[List[CollectionUpload]],
  previewPath: Option[FilePath],
  escapedPreviewPath: Option[FilePath]
)
object PackagePreviewMetadata {
  implicit val decodePreviewMetadata: Decoder[PackagePreviewMetadata] =
    deriveDecoder[PackagePreviewMetadata]
  implicit val encodePreviewMetadata: Encoder[PackagePreviewMetadata] =
    deriveEncoder[PackagePreviewMetadata]
}

object PackagePreview {
  implicit val decodePreview: Decoder[PackagePreview] =
    new Decoder[PackagePreview] {
      def apply(c: HCursor): Decoder.Result[PackagePreview] =
        for {
          metadata <- c.as[PackagePreviewMetadata]
          files <- c.downField("files").as[List[PreviewFile]]
        } yield new PackagePreview(metadata, files)
    }
  implicit val encodePreview: Encoder[PackagePreview] =
    new Encoder[PackagePreview] {
      def apply(preview: PackagePreview): Json = {
        val metadataObject = preview.metadata.asJson.asObject.get
        (("files" -> preview.files.asJson) +: metadataObject).asJson
      }
    }

  def fromFiles(
    fileUploads: List[FileUpload],
    importId: ImportId,
    maybeParent: Option[CollectionUpload],
    maybeAncestors: Option[List[CollectionUpload]]
  ): Either[Exception, PackagePreview] = {

    def getWarnings(files: NonEmptyList[FileUpload]): List[String] =
      files
        .filter(_.info.validate)
        .groupBy(_.fileType)
        .map {
          case (fileType, groupedFileUploads) =>
            fileType -> groupedFileUploads.filter(_.isMasterFile)
        }
        .filter {
          case (_, masterFiles) =>
            masterFiles.isEmpty
        }
        .flatMap {
          case (fileType, _) =>
            FileTypeInfo.get(fileType).masterExtension
        }
        .map { extension =>
          s"missing $extension file"
        }
        .toList

    def makeGeneric(files: NonEmptyList[FileUpload]): NonEmptyList[FileUpload] = {
      files.map(file => file.copy(fileType = FileType.Data, info = FileTypeInfo.get(FileType.Data)))
    }

    NonEmptyList
      .fromList(fileUploads)
      .toRight(new Exception("package preview cannot be constructed from an empty list of files"))
      .map { files =>
        val size = fileUploads.map(_.size).sum
        val warnings = getWarnings(files)

        // If a necessary master file is missing, then the files and package should be made Generic and should not be processed
        val transformedFiles =
          if (warnings.isEmpty) files else makeGeneric(files)

        val s3Files =
          (fileUploads ++ fileUploads.flatMap(_.annotations)).map(PreviewFile.apply)

        // If there is one, use the fileType of the first non-annotation file
        // Otherwise, use the fileType of the first annotation file
        val fileType = transformedFiles
          .find(_.info.grouping != FileTypeGrouping.Annotation)
          .getOrElse(transformedFiles.head)
          .fileType

        val packageInfo = FileTypeInfo.get(fileType)

        // If there is one, use the baseName of the package's first master file
        // Otherwise, use the name of the first file in the list
        // If the package type is Unknown, use the full file name (with extension) as the package name
        val masterFile = transformedFiles.find(_.isMasterFile)

        // If 2 or more files were merged into a single package (like for Persyst .lay and .dat files), the package
        // name should be the same as the constituent base name across all the merged files
        val packageName = masterFile match {
          case Some(mf) => mf.baseName
          case None => {
            val tf = transformedFiles.head
            tf.baseName + tf.extension
          }
        }

        val hasWorkflow = transformedFiles.exists(_.info.hasWorkflow)

        PackagePreview(
          metadata = PackagePreviewMetadata(
            packageName,
            escapeName(packageName),
            packageInfo.packageType,
            packageInfo.packageSubtype,
            fileType,
            warnings,
            size,
            hasWorkflow,
            importId,
            packageInfo.icon.toString,
            maybeParent,
            maybeAncestors,
            transformedFiles.head.filePath,
            transformedFiles.head.filePath.map(fp => fp.sanitize())
          ),
          files = s3Files
        )
      }
  }

  // Create a PackagePreview with an auto-generated importId
  def fromFiles(
    maybeParent: Option[CollectionUpload],
    maybeAncestors: Option[List[CollectionUpload]]
  )(
    fileUploads: List[FileUpload]
  ): Either[Exception, PackagePreview] =
    fromFiles(fileUploads, ImportId(UUID.randomUUID), maybeParent, maybeAncestors)
}

final case class ApiPackagePreview(
  packageName: String,
  escapedPackageName: String,
  packageType: PackageType,
  packageSubtype: String,
  fileType: FileType,
  files: List[UserFile],
  warnings: Iterable[String],
  groupSize: Long,
  hasWorkflow: Boolean,
  importId: ImportId,
  icon: String,
  parent: Option[CollectionUpload],
  ancestors: Option[List[CollectionUpload]]
)

object ApiPackagePreview {
  implicit val decodeApiPreview: Decoder[ApiPackagePreview] = deriveDecoder[ApiPackagePreview]
  implicit val encodeApiPreview: Encoder[ApiPackagePreview] = deriveEncoder[ApiPackagePreview]
}

final case class PreviewRequest(files: List[UserFile])

object PreviewRequest {
  implicit val encodePreviewRequest: Encoder[PreviewRequest] =
    deriveEncoder[PreviewRequest]

  implicit val decodePreviewRequest: Decoder[PreviewRequest] =
    deriveDecoder[PreviewRequest]
}

final case class PreviewPackageResponse(packages: List[PackagePreview])

object PreviewPackageResponse {
  implicit val decodePreviewResponse: Decoder[PreviewPackageResponse] =
    deriveDecoder[PreviewPackageResponse]

  implicit val encodePreviewResponse: Encoder[PreviewPackageResponse] =
    deriveEncoder[PreviewPackageResponse]
}

final case class MultipartUploadId(value: String) extends AnyVal

object MultipartUploadId {
  implicit val encoder: Encoder[MultipartUploadId] = Encoder[String].contramap(_.value)
  implicit val decoder: Decoder[MultipartUploadId] = Decoder[String].map(MultipartUploadId.apply)
}

final case class PreviewFile(
  uploadId: Int,
  fileName: String,
  escapedFileName: String,
  size: Long,
  multipartUploadId: Option[MultipartUploadId] = None,
  chunkedUpload: Option[ChunkedUpload] = None
) {
  def toUserFile(fileHash: FileHash): UserFile =
    UserFile(
      uploadId = uploadId,
      fileName = fileName,
      escapedFileName = escapedFileName,
      size = size,
      fileHash = Some(fileHash),
      chunkSize = chunkedUpload.map(_.chunkSize)
    )

  lazy val fileNameHash: String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(fileName.getBytes)
    encodeHex(ByteString(digest.digest()))
  }
}

object PreviewFile {
  implicit val encoderChunkedUpload: Encoder[ChunkedUpload] = deriveEncoder[ChunkedUpload]
  implicit val decoderChunkedUpload: Decoder[ChunkedUpload] = deriveDecoder[ChunkedUpload]

  implicit val encoderS3File: Encoder[PreviewFile] = deriveEncoder[PreviewFile]
  implicit val s3Decoder: Decoder[PreviewFile] = deriveDecoder[PreviewFile]

  def apply(file: FileUpload): PreviewFile =
    PreviewFile(
      file.uploadId,
      file.fileName,
      escapeName(file.fileName),
      file.size,
      chunkedUpload = Some(ChunkedUpload(file.size))
    )

  def apply(uploadId: Int, fileName: String, size: Long): PreviewFile =
    PreviewFile(
      uploadId,
      fileName,
      escapeName(fileName),
      size,
      chunkedUpload = Some(ChunkedUpload(size))
    )

  def apply(
    uploadId: Int,
    fileName: String,
    size: Long,
    multipartUploadId: String,
    chunkedUpload: ChunkedUpload
  ): PreviewFile =
    PreviewFile(
      uploadId,
      fileName,
      escapeName(fileName),
      size,
      Some(MultipartUploadId(multipartUploadId)),
      Some(chunkedUpload)
    )

  def apply(
    uploadId: Int,
    fileName: String,
    escapedFileName: String,
    size: Long,
    multipartUploadId: Option[MultipartUploadId] = None,
    chunkedUpload: Option[ChunkedUpload] = None
  ): PreviewFile =
    new PreviewFile(
      uploadId,
      fileName.replace("+", "%2B"),
      escapedFileName,
      size,
      multipartUploadId,
      chunkedUpload
    )
}

final case class ChunkedUpload(chunkSize: Long, totalChunks: Int)

object ChunkedUpload {
  private val FiveMebibytes = 5242880L
  private val S3MaxChunks = 10000L

  def apply(size: Long): ChunkedUpload = {
    val chunkSize = max(FiveMebibytes, ceil(size / S3MaxChunks.toDouble).toLong)
    val totalChunks = max(1, ceil(size.toDouble / chunkSize.toDouble).toInt)
    ChunkedUpload(chunkSize, totalChunks)
  }
}
