// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.alpakka

import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{ LocalDate, ZoneOffset, ZonedDateTime }

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ HttpHeader, HttpRequest }
import akka.stream.Materializer
import akka.stream.alpakka.s3.S3Settings
import akka.stream.alpakka.s3.auth.{ digest, encodeHex }
import com.amazonaws.auth
import com.amazonaws.auth.AWSCredentialsProvider
import com.blackfynn.upload.model.Eventual.{ Eventual, EventualTConverter }

import scala.concurrent.{ ExecutionContext, Future }

object Signer {
  private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")

  def createSignedRequestT(
    request: HttpRequest,
    maybeHash: Option[String] = None
  )(implicit
    mat: Materializer,
    ec: ExecutionContext,
    settings: S3Settings
  ): Eventual[HttpRequest] =
    createSignedRequest(request, maybeHash).toEventual

  def createSignedRequest(
    request: HttpRequest,
    maybeHash: Option[String] = None
  )(implicit
    mat: Materializer,
    ec: ExecutionContext,
    settings: S3Settings
  ): Future[HttpRequest] =
    signedRequest(
      request = request,
      key = SigningKey(
        settings.credentialsProvider,
        CredentialScope(LocalDate.now(), settings.s3RegionProvider.getRegion, "s3")
      ),
      maybeHash = maybeHash
    )

  def signedRequest(
    request: HttpRequest,
    key: SigningKey,
    date: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    maybeHash: Option[String] = None
  )(implicit
    mat: Materializer,
    ec: ExecutionContext
  ): Future[HttpRequest] = {
    val hashedBody = maybeHash.fold(consumeEntityAndCreateHash(request))(Future.successful)

    hashedBody.map { hb =>
      val headersToAdd = Vector(
        RawHeader("x-amz-date", date.format(dateFormatter)),
        RawHeader("x-amz-content-sha256", hb)
      ) ++ sessionHeader(key.credProvider)
      val reqWithHeaders = request.withHeaders(request.headers ++ headersToAdd)
      val cr = CanonicalRequest.from(reqWithHeaders)
      val authHeader = authorizationHeader("AWS4-HMAC-SHA256", key, date, cr)
      reqWithHeaders.withHeaders(reqWithHeaders.headers :+ authHeader)
    }
  }

  private def consumeEntityAndCreateHash(
    request: HttpRequest
  )(implicit
    mat: Materializer,
    ec: ExecutionContext
  ) =
    request.entity.dataBytes.runWith(digest()).map(hash => encodeHex(hash.toArray))

  private[this] def sessionHeader(creds: AWSCredentialsProvider): Option[HttpHeader] =
    creds.getCredentials match {
      case sessCreds: auth.AWSSessionCredentials =>
        Some(RawHeader("X-Amz-Security-Token", sessCreds.getSessionToken))
      case _ => None
    }

  private[this] def authorizationHeader(
    algorithm: String,
    key: SigningKey,
    requestDate: ZonedDateTime,
    canonicalRequest: CanonicalRequest
  ): HttpHeader =
    RawHeader("Authorization", authorizationString(algorithm, key, requestDate, canonicalRequest))

  private[this] def authorizationString(
    algorithm: String,
    key: SigningKey,
    requestDate: ZonedDateTime,
    canonicalRequest: CanonicalRequest
  ): String = {
    val sign = key.hexEncodedSignature(
      stringToSign(algorithm, key, requestDate, canonicalRequest).getBytes()
    )
    s"$algorithm Credential=${key.credentialString}, SignedHeaders=${canonicalRequest.signedHeaders}, Signature=$sign"
  }

  def stringToSign(
    algorithm: String,
    signingKey: SigningKey,
    requestDate: ZonedDateTime,
    canonicalRequest: CanonicalRequest
  ): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashedRequest = encodeHex(digest.digest(canonicalRequest.canonicalString.getBytes()))
    val date = requestDate.format(dateFormatter)
    val scope = signingKey.scope.scopeString
    s"$algorithm\n$date\n$scope\n$hashedRequest"
  }

}
