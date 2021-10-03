// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import io.circe.{ Decoder, Encoder, HCursor }
import io.circe.generic.semiauto.{ deriveEncoder }

case class UserFile(
  uploadId: Int,
  fileName: String,
  escapedFileName: String,
  size: Long,
  fileHash: Option[FileHash], // TODO: after integration with API, make this non-optional
  chunkSize: Option[Long], // TODO: after integration with API, make this non-optional
  filePath: Option[FilePath] = None
)

object UserFile {

  private def cleanS3Key(key: String): String =
    key.replaceAll("[^a-zA-Z0-9./@-]", "_")

  implicit val encoder: Encoder[UserFile] = deriveEncoder[UserFile]

  implicit val decoder: Decoder[UserFile] = new Decoder[UserFile] {
    final def apply(c: HCursor): Decoder.Result[UserFile] =
      for {
        uploadId <- c.downField("uploadId").as[Int]
        fileName <- c.downField("fileName").as[String]
        escapedFileName <- c.getOrElse("escapedFileName")(cleanS3Key(fileName))
        size <- c.downField("size").as[Long]
        fileHash <- c.downField("fileHash").as[Option[FileHash]]
        chunkSize <- c.downField("chunkSize").as[Option[Long]]
        filePath <- c.downField("filePath").as[Option[FilePath]]
      } yield {
        new UserFile(
          uploadId = uploadId,
          fileName = fileName,
          escapedFileName = escapedFileName,
          size = size,
          fileHash = fileHash,
          chunkSize = chunkSize,
          filePath = filePath
        )
      }
  }

  def apply(
    uploadId: Int,
    fileName: String,
    size: Long,
    fileHash: Option[FileHash],
    chunkSize: Option[Long]
  ): UserFile = {
    new UserFile(
      uploadId = uploadId,
      fileName = fileName,
      escapedFileName = cleanS3Key(fileName),
      size = size,
      fileHash = fileHash,
      chunkSize = chunkSize,
      filePath = None
    )
  }

  def apply(
    uploadId: Int,
    fileName: String,
    size: Long,
    fileHash: Option[FileHash],
    chunkSize: Option[Long],
    filePath: Option[FilePath]
  ): UserFile = {
    new UserFile(
      uploadId = uploadId,
      fileName = fileName,
      escapedFileName = cleanS3Key(fileName),
      size = size,
      fileHash = fileHash,
      chunkSize = chunkSize,
      filePath = filePath
    )
  }

  def apply(
    uploadId: Int,
    fileName: String,
    escapedFileName: String,
    size: Long,
    fileHash: Option[FileHash],
    chunkSize: Option[Long],
    filePath: Option[FilePath] = None
  ): UserFile = {
    new UserFile(
      uploadId = uploadId,
      fileName = fileName,
      escapedFileName = escapedFileName,
      size = size,
      fileHash = fileHash,
      chunkSize = chunkSize,
      filePath = filePath
    )
  }
}
