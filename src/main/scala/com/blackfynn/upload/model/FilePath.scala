// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import io.circe.syntax.EncoderOps
import io.circe.{ Decoder, Encoder, Json }
import com.pennsieve.models.Utilities.cleanS3Key

// wasString ensures that if a filePath is sent as a string it will be returned as a string
final case class FilePath(segments: List[String], wasString: Boolean = false) {

  def sanitize(): FilePath = {
    copy(segments.map(cleanS3Key))
  }
}

object FilePath {
  def apply(segments: String*): FilePath = apply(segments.toList)
  @deprecated("Should be removed once all clients have been migrated", "2/7/2019")
  val stringDecoder: Decoder[FilePath] =
    Decoder[String]
      .map {
        case """/""" => FilePath(List.empty[String], wasString = true)
        case path => FilePath(path.split("/").toList, wasString = true)
      }

  implicit val encoder: Encoder[FilePath] =
    Encoder[Json]
      .contramap { filePath =>
        if (filePath.wasString) filePath.segments.mkString("/").asJson
        else filePath.segments.asJson
      }

  implicit val decoder: Decoder[FilePath] =
    Decoder[List[String]]
      .map(dirs => FilePath(dirs))
      .or(stringDecoder)
}
