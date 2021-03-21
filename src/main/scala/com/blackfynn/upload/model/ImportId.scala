// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import java.util.UUID

import io.circe.{ Decoder, Encoder }

import scala.util.Try

final case class ImportId(value: UUID) extends AnyVal {
  override def toString: String = value.toString
}

object ImportId {
  implicit val encoder: Encoder[ImportId] =
    Encoder[String].contramap(_.toString)
  implicit val decoder: Decoder[ImportId] =
    Decoder[String].emapTry(string => Try(ImportId(UUID.fromString(string))))

  def apply(importIdString: String): Either[Throwable, ImportId] =
    Try(UUID.fromString(importIdString)).toEither.map(ImportId.apply)
}
