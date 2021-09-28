// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model
import com.pennsieve.concepts.ProxyTargetLinkRequest
import io.circe.{ Decoder, Encoder }
import io.circe.generic.semiauto._

case class UploadCompleteRequest(preview: ApiPackagePreview, proxyLink: Option[ProxyLink])

object UploadCompleteRequest {
  implicit val encoder: Encoder[UploadCompleteRequest] = deriveEncoder[UploadCompleteRequest]
  implicit val decoder: Decoder[UploadCompleteRequest] = deriveDecoder[UploadCompleteRequest]
}

case class ProxyLink(conceptId: String, instanceId: String, targets: List[ProxyTargetLinkRequest])

object ProxyLink {
  implicit val proxyTargetEncoder: Encoder[ProxyTargetLinkRequest] =
    deriveEncoder[ProxyTargetLinkRequest]
  implicit val proxyTargetDecoder: Decoder[ProxyTargetLinkRequest] =
    deriveDecoder[ProxyTargetLinkRequest]

  implicit val encoder: Encoder[ProxyLink] = deriveEncoder[ProxyLink]
  implicit val decoder: Decoder[ProxyLink] = deriveDecoder[ProxyLink]
}
