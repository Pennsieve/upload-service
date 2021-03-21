// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.routing

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import cats.implicits._
import com.blackfynn.auth.middleware.Jwt.Claim
import com.blackfynn.auth.middleware.UserId
import com.blackfynn.service.utilities.{ ContextLogger, Tier }
import com.blackfynn.upload.model.ImportId
import com.blackfynn.upload._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.ExecutionContext

/**
  * Generate and record the hash of an entire file for verifying file integrity
  */
object HashRouting {
  type GetHash = HashRouting.type
  implicit val tier: Tier[GetHash] = Tier[GetHash]

  def apply(
    claim: Claim
  )(implicit
    ports: UploadPorts,
    ec: ExecutionContext,
    mat: Materializer,
    log: ContextLogger
  ): Route =
    (path("hash" / "id" / Segment) & parameters(('fileName, 'userId.as[Int].?))) {
      (importIdString, fileName, maybeUserId) =>
        implicit val hashPorts: HashPorts = ports.hash

        (ImportId(importIdString), maybeUserId, getUserInfo(claim)) match {
          // try to read service claim
          case (Right(importId), Some(intUserId), _) => {
            val userId = UserId(intUserId)
            implicit val logContext: UploadLogContext = UploadLogContext(importId, userId, fileName)
            log.tierContext.info("HashRouting: service request")
            respond(importId, fileName)
          }

          // fall back to a user claim
          case (Right(importId), _, Some((_, userId))) => {
            implicit val logContext: UploadLogContext = UploadLogContext(importId, userId, fileName)
            log.tierContext.info("HashRouting: user request")
            respond(importId, fileName)
          }

          case (Right(_), None, _) =>
            complete(
              badRequest(s"userId must be provided as a query param when using a service claim")
            )

          case (Right(_), _, None) => complete(unauthorizedResponse)

          case (Left(e), _, _) => complete(badRequest(s"invalid import id: ${e.getMessage}"))
        }
    }

  private def respond(
    importId: ImportId,
    fileName: String
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    log: ContextLogger,
    logContext: UploadLogContext,
    ports: HashPorts
  ) =
    complete {
      Hash(importId, fileName)
        .fold[ToResponseMarshallable]({
          case MissingHashes(message) =>
            log.tierContext.warn("hash not found")
            NotFound -> message
          case e => handleExceptions(e)
        }, fileHash => fileHash)
    }
}
