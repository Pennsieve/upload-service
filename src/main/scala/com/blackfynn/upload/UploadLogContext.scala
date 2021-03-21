// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import com.blackfynn.auth.middleware.UserId
import com.blackfynn.service.utilities.LogContext
import com.blackfynn.upload.model.ImportId

final case class UploadLogContext(
  importId: Option[ImportId] = None,
  userId: Option[UserId] = None,
  fileName: Option[String] = None
) extends LogContext {
  override val values: Map[String, String] = inferValues(this)
}

object UploadLogContext {

  def apply(userId: UserId): UploadLogContext =
    UploadLogContext(None, Some(userId), None)

  def apply(importId: ImportId): UploadLogContext =
    UploadLogContext(Some(importId), None, None)

  def apply(userId: UserId, fileName: String): UploadLogContext =
    UploadLogContext(None, Some(userId), Some(fileName))

  def apply(importId: ImportId, userId: UserId, fileName: String): UploadLogContext =
    UploadLogContext(Some(importId), Some(userId), Some(fileName))

  def apply(importId: ImportId, userId: UserId): UploadLogContext =
    UploadLogContext(Some(importId), Some(userId), None)
}
