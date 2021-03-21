// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.alpakka.s3.impl.MultipartUpload
import akka.stream.scaladsl.Source
import cats.data.NonEmptyList
import com.amazonaws.services.s3.model.PartListing
import com.blackfynn.upload.ChunkPorts.{ CacheHash, SendChunk }
import com.blackfynn.upload.CompletePorts._
import com.blackfynn.upload.HashPorts.GetChunkHashes
import com.blackfynn.upload.PreviewPorts.{ CachePreview, InitiateUpload }
import com.blackfynn.upload.UploadPorts._
import com.blackfynn.upload.model.Eventual.Eventual
import com.blackfynn.upload.model._

final case class UploadPorts(
  chunk: ChunkPorts,
  preview: PreviewPorts,
  complete: CompletePorts,
  status: StatusPorts,
  hash: HashPorts
)

object UploadPorts {
  type ListParts = HttpRequest => Eventual[Option[PartListing]]
  type GetPreview = ImportId => Eventual[PackagePreview]
  type CheckFileExists = HttpRequest => Eventual[Boolean]

  def apply(
    chunk: ChunkPorts,
    preview: PreviewPorts,
    complete: CompletePorts,
    hash: HashPorts
  ): UploadPorts =
    UploadPorts(chunk, preview, complete, StatusPorts(complete), hash)
}

final case class CompletePorts(
  listParts: ListParts,
  completeUpload: CompleteUpload,
  getPreview: GetPreview,
  sendComplete: SendComplete,
  checkFileExists: CheckFileExists
)

object CompletePorts {
  type CompleteUpload = HttpRequest => Eventual[Unit]
  type SendComplete = HttpRequest => Eventual[HttpResponse]
}

final case class PreviewPorts(cachePreview: CachePreview, initiateUpload: InitiateUpload)

object PreviewPorts {
  type CachePreview = PackagePreview => Eventual[Unit]
  type InitiateUpload = HttpRequest => Eventual[MultipartUpload]
}

final case class StatusPorts(
  listParts: ListParts,
  getPreview: GetPreview,
  checkFileExists: CheckFileExists
)

object StatusPorts {
  def apply(complete: CompletePorts): StatusPorts =
    StatusPorts(complete.listParts, complete.getPreview, complete.checkFileExists)
}

final case class ChunkPorts(sendChunk: SendChunk, cacheHash: CacheHash)

object ChunkPorts {
  type CacheHash = ChunkHash => Eventual[Unit]
  type SendChunk = HttpRequest => Eventual[Unit]
}

case class HashPorts(getChunkHashes: GetChunkHashes, getPreview: GetPreview)

object HashPorts {
  type GetChunkHashes =
    (NonEmptyList[ChunkHashId], ImportId) => Source[Either[Throwable, ChunkHash], _]
}
