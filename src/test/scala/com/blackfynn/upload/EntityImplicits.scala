// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload
import akka.http.scaladsl.model.HttpEntity
import akka.stream.Materializer
import akka.util.ByteString
import com.blackfynn.test.AwaitableImplicits

import scala.concurrent.ExecutionContext

object EntityImplicits extends AwaitableImplicits {
  implicit class RichHttpEntity(entity: HttpEntity) {
    def getString(implicit mat: Materializer, ec: ExecutionContext): String =
      entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String).awaitFinite()
  }
}
