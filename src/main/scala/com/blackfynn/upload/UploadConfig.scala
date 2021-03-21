// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import com.amazonaws.regions.Regions
import com.blackfynn.auth.middleware.Jwt

final case class UploadConfig(
  host: String,
  port: Int,
  jwtKey: String,
  preview: PreviewConfig,
  complete: CompleteConfig,
  status: StatusConfig,
  s3: S3Config,
  apiBaseUrl: String,
  chunkHashTableName: String,
  previewMetadataTableName: String,
  previewFilesTableName: String,
  maxRequestsPerSecond: Int,
  maxChunkSize: Long
) {
  val jwt: Jwt.Config = new Jwt.Config {
    val key: String = jwtKey
  }
}

final case class CompleteConfig(parallelism: Int)
final case class StatusConfig(listPartsParallelism: Int)

final case class JwtConfig(key: String)

final case class S3Config(bucket: String, region: String, connectionPoolQueueSize: Int) {
  val awsRegion: Regions = Regions.fromName(region)
}

final case class PreviewConfig(
  parallelism: Int,
  batchWriteParallelism: Int,
  batchWriteMaxRetries: Int
)
