// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package akka.stream.alpakka.s3
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.RawHeader

/**
  * We've used Alpakka methods and objects in their impl package in our own code.
  * In newer versions of Alpakka this privacy is enforced by the compliler, so this
  * object is a hack to help bridge the gap.
  */
object ImplHelper {
  implicit class S3HeadersToSeq(s3Headers: S3Headers) {
    def toSeq(): Seq[HttpHeader] = {
      s3Headers.cannedAcl.toIndexedSeq.map(_.header) ++
        s3Headers.metaHeaders.toIndexedSeq.flatMap(_.headers) ++
        s3Headers.storageClass.toIndexedSeq.map(_.header) ++
        s3Headers.customHeaders.map { header =>
          RawHeader(header._1, header._2)
        }
    }
  }

}
