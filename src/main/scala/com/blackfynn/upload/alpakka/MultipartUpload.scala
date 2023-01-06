// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.alpakka
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.alpakka.s3.S3Headers

case class S3Location(bucket: String, key: String)
case class MultipartUpload(s3Location: S3Location, uploadId: String)
