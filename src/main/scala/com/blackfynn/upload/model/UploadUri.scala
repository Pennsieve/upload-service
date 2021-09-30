// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import com.pennsieve.auth.middleware.UserId
import com.blackfynn.upload.model.Constants.PreviewKey

case class UploadUri(userId: UserId, importId: ImportId, name: String) {
  override def toString: String = s"${userId.value}/$importId/$s3SafeName"

  def s3SafeName = cleanS3Key(name)
  def previewUri = UploadPreviewUri(userId, importId)

  private def cleanS3Key(key: String): String =
    key.replaceAll("[^a-zA-Z0-9./@-]", "_")
}

object UploadUri {
  def apply(userId: UserId, importId: ImportId, name: String) = {
    new UploadUri(userId, importId, name)
  }
}

case class UploadPreviewUri(userId: UserId, importId: ImportId) {
  private val uploadUri: UploadUri = UploadUri(userId, importId, PreviewKey)
  override def toString = s"previews/$uploadUri"
}

object UploadPreviewUri {
  def apply(uriFromName: String => UploadUri): UploadPreviewUri = uriFromName(PreviewKey).previewUri
}
