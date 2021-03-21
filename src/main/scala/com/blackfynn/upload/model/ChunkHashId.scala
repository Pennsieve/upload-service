// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import java.security.MessageDigest

import akka.util.ByteString

final case class ChunkHashId(chunkNumber: Int, importId: ImportId, name: String) {
  val value: String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(s"$importId-$name-$chunkNumber".getBytes)
    ByteString(digest.digest()).utf8String
  }
}

object ChunkHashId {
  def apply(uri: UploadUri, chunkNumber: Int): ChunkHashId =
    new ChunkHashId(chunkNumber, uri.importId, uri.name)
}

final case class ChunkHash(id: ChunkHashId, importId: ImportId, hash: String)
