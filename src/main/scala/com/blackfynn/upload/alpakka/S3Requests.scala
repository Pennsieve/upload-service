// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.alpakka

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.Uri.{ Authority, Query }
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.{ ContentTypes, RequestEntity, _ }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.stream.alpakka.s3.{ S3Headers, S3Settings }
import com.blackfynn.upload.model.Eventual.{ Eventual, EventualTConverter }
import akka.stream.alpakka.s3.ImplHelper._

import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.xml.NodeSeq

object S3Requests {

  implicit val multipartUploadUnmarshaller: FromEntityUnmarshaller[MultipartUpload] =
    nodeSeqUnmarshaller(MediaTypes.`application/xml`, ContentTypes.`application/octet-stream`) map {
      case NodeSeq.Empty => throw Unmarshaller.NoContentException
      case x =>
        MultipartUpload(S3Location((x \ "Bucket").text, (x \ "Key").text), (x \ "UploadId").text)
    }

  def initiateMultipartUploadRequest(
    s3Location: S3Location,
    s3Headers: S3Headers,
    contentType: ContentType = ContentTypes.`application/octet-stream`
  )(implicit
    conf: S3Settings
  ): HttpRequest =
    s3Request(s3Location, HttpMethods.POST, _.withQuery(Query("uploads")))
      .withDefaultHeaders(s3Headers.toSeq())
      .withEntity(HttpEntity.empty(contentType))

  def uploadPartRequest(
    upload: MultipartUpload,
    partNumber: Int,
    entity: RequestEntity,
    s3Headers: S3Headers = S3Headers.empty
  )(implicit
    conf: S3Settings
  ): HttpRequest =
    s3Request(
      upload.s3Location,
      HttpMethods.PUT,
      _.withQuery(Query("partNumber" -> partNumber.toString, "uploadId" -> upload.uploadId))
    ).withDefaultHeaders(s3Headers.toSeq())
      .withEntity(entity)

  def completeMultipartUploadRequest(
    upload: MultipartUpload,
    parts: Seq[(Int, String)]
  )(implicit
    ec: ExecutionContext,
    conf: S3Settings
  ): Future[HttpRequest] = {

    //Do not let the start PartNumber,ETag and the end PartNumber,ETag be on different lines
    //  They tend to get split when this file is formatted by IntelliJ unless http://stackoverflow.com/a/19492318/1216965
    // @formatter:off
    val payload = <CompleteMultipartUpload>
      {
      parts.map { case (partNumber, etag) => <Part><PartNumber>{ partNumber }</PartNumber><ETag>{ etag }</ETag></Part> }
      }
    </CompleteMultipartUpload>
    // @formatter:on

    for {
      entity <- Marshal(payload).to[RequestEntity]
    } yield {
      s3Request(
        upload.s3Location,
        HttpMethods.POST,
        _.withQuery(Query("uploadId" -> upload.uploadId))
      ).withEntity(entity)
    }
  }

  def completeMultipartUploadRequestT(
    upload: MultipartUpload,
    parts: Seq[(Int, String)]
  )(implicit
    ec: ExecutionContext,
    conf: S3Settings
  ): Eventual[HttpRequest] =
    completeMultipartUploadRequest(upload, parts).toEventual

  def listMultipartUploadRequest(
    upload: MultipartUpload,
    marker: Int = 0
  )(implicit
    conf: S3Settings
  ): HttpRequest =
    s3Request(
      upload.s3Location,
      HttpMethods.GET,
      _.withQuery(Query("uploadId" -> upload.uploadId, "part-number-marker" -> marker.toString))
    )

  def headObjectRequest(s3Location: S3Location)(implicit conf: S3Settings): HttpRequest =
    s3Request(s3Location, HttpMethods.HEAD)

  private[this] def s3Request(
    s3Location: S3Location,
    method: HttpMethod,
    uriFn: Uri => Uri = identity
  )(implicit
    conf: S3Settings
  ): HttpRequest =
    HttpRequest(method)
      .withHeaders(
        Host(requestAuthority(s3Location.bucket, conf.s3RegionProvider.getRegion.toString))
      )
      .withUri(uriFn(requestUri(s3Location.bucket, Some(s3Location.key))))

  @throws(classOf[IllegalUriException])
  private[this] def requestAuthority(
    bucket: String,
    region: String
  )(implicit
    conf: S3Settings
  ): Authority =
    conf.forwardProxy match {
      case None =>
        if (!conf.pathStyleAccess) {
          val bucketRegex = "[^a-z0-9\\-\\.]{1,255}|[\\.]{2,}".r
          bucketRegex.findFirstIn(bucket) match {
            case Some(illegalCharacter) =>
              throw IllegalUriException(
                "Bucket name contains non-LDH characters",
                s"""The following character is not allowed: $illegalCharacter
                   | This may be solved by setting akka.stream.alpakka.s3.path-style-access to true in the configuration.
                 """.stripMargin
              )
            case None => ()
          }
        }
        (region, conf.endpointUrl) match {
          case (_, Some(endpointUrl)) =>
            Uri(endpointUrl).authority
          case ("us-east-1", _) =>
            if (conf.pathStyleAccess) {
              Authority(Uri.Host("s3.amazonaws.com"))
            } else {
              Authority(Uri.Host(s"$bucket.s3.amazonaws.com"))
            }
          case _ =>
            if (conf.pathStyleAccess) {
              Authority(Uri.Host(s"s3-$region.amazonaws.com"))
            } else {
              Authority(Uri.Host(s"$bucket.s3-$region.amazonaws.com"))
            }
        }
      case Some(proxy) => Authority(Uri.Host(proxy.host))
    }

  private[this] def requestUri(
    bucket: String,
    key: Option[String]
  )(implicit
    conf: S3Settings
  ): Uri = {
    val basePath = if (conf.pathStyleAccess) {
      Uri.Path / bucket
    } else {
      Uri.Path.Empty
    }
    val path = key.fold(basePath) { someKey =>
      someKey.split("/").foldLeft(basePath)((acc, p) => acc / p)
    }
    val uri =
      Uri(
        path = path,
        authority = requestAuthority(bucket, conf.s3RegionProvider.getRegion.toString)
      )

    (conf.forwardProxy, conf.endpointUrl) match {
      case (_, Some(endpointUri)) =>
        uri
          .withScheme(Uri(endpointUri).scheme)
          .withHost(requestAuthority(bucket, conf.s3RegionProvider.getRegion.toString).host)
      case (None, _) =>
        uri
          .withScheme("https")
          .withHost(requestAuthority(bucket, conf.s3RegionProvider.getRegion.toString).host)
      case (Some(proxy), _) =>
        uri.withPort(proxy.port).withScheme(proxy.scheme).withHost(proxy.host)
    }
  }
}
