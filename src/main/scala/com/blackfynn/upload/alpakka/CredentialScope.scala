// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.alpakka

import java.time.LocalDate
import java.time.format.DateTimeFormatter

final case class CredentialScope(date: LocalDate, awsRegion: String, awsService: String) {
  lazy val formattedDate: String = date.format(DateTimeFormatter.BASIC_ISO_DATE)

  def scopeString = s"$formattedDate/$awsRegion/$awsService/aws4_request"
}
