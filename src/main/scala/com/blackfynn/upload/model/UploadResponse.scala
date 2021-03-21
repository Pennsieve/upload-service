// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }

case class UploadResponse(success: Boolean, error: Option[String])

object UploadResponse {
  implicit val decoder: Decoder[UploadResponse] = deriveDecoder[UploadResponse]
  implicit val encoder: Encoder[UploadResponse] = deriveEncoder[UploadResponse]
}
