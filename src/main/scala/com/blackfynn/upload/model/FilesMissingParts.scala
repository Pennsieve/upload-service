// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

final class FileMissingParts(
  val fileName: String,
  val missingParts: List[Int],
  val expectedTotalParts: Int
) {
  require(missingParts.forall(i => i >= 0 && i <= 9999), "Parts must exist between 0 and 9999")

  override def toString: String =
    s"fileName=$fileName missingParts=$missingParts expectedTotalParts=$expectedTotalParts"
}

object FileMissingParts {

  def apply(filename: String, fileParts: Set[Int], expectedTotalParts: Int): FileMissingParts = {
    val missingParts: List[Int] =
      (1 to expectedTotalParts).toSet
        .diff(fileParts)
        .toList
        .map(_ - 1)
        .sorted

    new FileMissingParts(filename.replace("%2B", "+"), missingParts, expectedTotalParts)
  }
}

final case class FilesMissingParts(files: List[FileMissingParts])

object FilesMissingParts {
  implicit val decoderFileMissingParts: Decoder[FileMissingParts] = deriveDecoder[FileMissingParts]
  implicit val encoderFileMissingParts: Encoder[FileMissingParts] = deriveEncoder[FileMissingParts]

  implicit val decoder: Decoder[FilesMissingParts] = deriveDecoder[FilesMissingParts]
  implicit val encoder: Encoder[FilesMissingParts] = deriveEncoder[FilesMissingParts]
}
