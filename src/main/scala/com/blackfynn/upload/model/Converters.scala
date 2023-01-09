// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import com.blackfynn.upload.UploadConfig
import com.blackfynn.upload.alpakka.{ MultipartUpload, S3Location }

object Converters {

  def multipartUpload(
    uri: UploadUri,
    multipartId: MultipartUploadId
  )(implicit
    config: UploadConfig
  ) =
    MultipartUpload(S3Location(config.s3.bucket, uri.toString), multipartId.value)

}
