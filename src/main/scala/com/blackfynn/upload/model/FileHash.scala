// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import akka.stream.alpakka.s3.auth.encodeHex
import akka.util.ByteString
import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.{ Decoder, Encoder }

final case class FileHash(hash: String)

object FileHash {

  implicit val encoder: Encoder[FileHash] = deriveEncoder[FileHash]

  private val objectDecoder: Decoder[FileHash] = deriveDecoder[FileHash]

  private val stringDecoder: Decoder[FileHash] = Decoder[String].map { hash =>
    FileHash(hash)
  }

  implicit def decoder: Decoder[FileHash] =
    List[Decoder[FileHash]](objectDecoder, stringDecoder)
      .reduceLeft(_ or _)

  def apply(hash: Array[Byte]): FileHash = FileHash(encodeHex(ByteString(hash)))
}
