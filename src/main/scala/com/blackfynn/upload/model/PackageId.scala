// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import cats.implicits._
import com.pennsieve.models.NodeCodes.{ collectionCode, packageCode }
import com.pennsieve.models.NodeId
import io.circe.{ Decoder, Encoder }

trait PackageId {
  val value: String

  override def toString: String = value
}

trait PackageIdCompanion {
  type A

  val code: String

  def apply(value: String): A

  def fromString(string: String): Either[Throwable, A] =
    NodeId
      .fromString(string)
      .flatMap { nodeId =>
        if (nodeId.nodeCode == code) apply(nodeId.toString).asRight[Exception]
        else InvalidNodeCode(string).asLeft
      }

  def apply(maybeId: Option[String]): Option[Either[Throwable, A]] =
    maybeId map fromString

  def apply(): A = apply(NodeId.generateForCode(code).toString)

  implicit val encoder: Encoder[A] = Encoder[String].contramap(_.toString)

  implicit val decoder: Decoder[A] =
    Decoder[String] emap { string =>
      NodeId
        .fromString(string)
        .bimap(_.getMessage(), nodeId => apply(nodeId.toString))
    }
}

final case class FileId(value: String) extends PackageId

object FileId extends PackageIdCompanion {
  override type A = FileId
  override val code: String = packageCode
}

final case class CollectionId(value: String) extends PackageId

object CollectionId extends PackageIdCompanion {
  override type A = CollectionId
  override val code: String = collectionCode
}
