// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import akka.stream.alpakka.s3.impl.{ MultipartUpload, S3Location }
import com.blackfynn.upload.UploadConfig

object Converters {

  def multipartUpload(
    uri: UploadUri,
    multipartId: MultipartUploadId
  )(implicit
    config: UploadConfig
  ) =
    MultipartUpload(S3Location(config.s3.bucket, uri.toString), multipartId.value)

}
