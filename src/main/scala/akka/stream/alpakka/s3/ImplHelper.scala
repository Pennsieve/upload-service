// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package akka.stream.alpakka.s3
import akka.NotUsed
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import java.security.MessageDigest

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

  private val Digits = "0123456789abcdef".toCharArray
  // copied from akka.stream.alpakka.s3.impl.auth#encodeHex
  def encodeHex(bytes: Array[Byte]): String = {
    val length = bytes.length
    val out = new Array[Char](length * 2)
    for (i <- 0 until length) {
      val b = bytes(i)
      out(i * 2) = Digits((b >> 4) & 0xF)
      out(i * 2 + 1) = Digits(b & 0xF)
    }
    new String(out)
  }

  def encodeHex(bytes: ByteString): String = encodeHex(bytes.toArray)

  def digest(algorithm: String = "SHA-256"): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString]
      .fold(MessageDigest.getInstance(algorithm)) {
        case (digest, bytes) =>
          digest.update(bytes.asByteBuffer)
          digest
      }
      .map(d => ByteString(d.digest()))

}
