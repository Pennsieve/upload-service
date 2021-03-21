// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

final case class CollectionUpload(
  id: CollectionId,
  name: String,
  parentId: Option[CollectionId],
  depth: Int
)

object CollectionUpload {
  implicit val encoder: Encoder[CollectionUpload] =
    deriveEncoder[CollectionUpload]
  implicit val decoder: Decoder[CollectionUpload] =
    deriveDecoder[CollectionUpload]
}

final case class InvalidNodeCode(id: String) extends Exception {
  override def getMessage: String =
    s"Destination package must be either a collection N:collection:UUID or a package N:package:UUID received $id"
}
