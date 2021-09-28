// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import akka.NotUsed
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model.StatusCodes.{
  BadRequest,
  InternalServerError,
  Locked,
  NotFound,
  TooManyRequests => TooManyRequests_,
  Unauthorized
}
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse, StatusCode }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ PathMatcher, RequestContext, Route }
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.pennsieve.auth.middleware.Jwt.{ DatasetRole, OrganizationRole, Role }
import com.pennsieve.auth.middleware.{
  DatasetId,
  DatasetPermission,
  EncryptionKeyId,
  Jwt,
  UserClaim,
  UserId,
  Validator,
  Wildcard
}
import com.pennsieve.service.utilities.{ ContextLogger, LogContext, Tier }
import com.blackfynn.upload.model.UploadResponse
import io.circe.syntax.EncoderOps
import shapeless.syntax.inject.InjectSyntax

package object routing {

  case object TooManyRequests extends Exception("Too many requests")

  val unauthorizedResponse: HttpResponse =
    HttpResponse(Unauthorized, entity = HttpEntity("Sender does not have permission to upload"))

  val unauthorizedRoute: Route =
    complete(unauthorizedResponse)

  val lockedResponse: HttpResponse =
    HttpResponse(Locked, entity = HttpEntity("requested dataset is locked"))

  val lockedRoute: Route =
    complete(lockedResponse)

  def handleExceptions(
    e: Throwable
  )(implicit
    log: ContextLogger,
    context: LogContext,
    tier: Tier[_]
  ): ToResponseMarshallable = {
    log.tierContext.error(s"failed with ${e.getMessage} \n ${e.getStackTrace.mkString("\n")}")
    InternalServerError
  }

  def handleExceptionsNoContext[A](
    e: Throwable
  )(implicit
    log: ContextLogger,
    tier: Tier[A]
  ): ToResponseMarshallable = {
    log.tierNoContext[A].error(s"failed with ${e.getMessage} \n ${e.getStackTrace.mkString("\n")}")
    InternalServerError
  }

  def invalidImportIdResponse(importIdString: String, err: Throwable): HttpResponse =
    badRequest(s"Invalid upload id $importIdString with ${err.getMessage}")

  val baseUploadPath: PathMatcher[(String, String)] =
    "organizations" / Segment / "id" / Segment

  def response(status: StatusCode, message: String) =
    HttpResponse(status, entity = HttpEntity(message))

  def badRequest(message: String = "request had invalid format") =
    HttpResponse(BadRequest, entity = HttpEntity(message))

  def notFound(message: String) =
    HttpResponse(NotFound, entity = HttpEntity(message))

  def tooManyRequests = HttpResponse(TooManyRequests_)

  def internalServerError(err: Throwable): HttpResponse =
    HttpResponse(InternalServerError, entity = HttpEntity(err.getMessage))

  def cancelEntity()(implicit mat: Materializer, requestContext: RequestContext): NotUsed =
    requestContext.request.entity.dataBytes.runWith(Sink.cancelled)

  def createUploadResponse(success: Boolean, error: Option[String] = None): Strict =
    HttpEntity(`application/json`, UploadResponse(success, error).asJson.noSpaces)

  def hasDatasetAccess(
    claim: Jwt.Claim,
    datasetId: Option[DatasetId]
  )(
    f: (UserId, EncryptionKeyId) => Route
  ): Route =
    claim.content match {
      case UserClaim(userId, roles, _, _) =>
        if (checkDatasetAccess(claim, datasetId))
          getEncryptionId(roles).fold(unauthorizedRoute) { encryptionKeyId =>
            f(userId, encryptionKeyId)
          } else unauthorizedRoute

      case _ => unauthorizedRoute
    }

  def hasUnlockedDatasetAccess(
    claim: Jwt.Claim,
    datasetId: Option[DatasetId]
  )(
    f: (UserId, EncryptionKeyId) => Route
  ): Route =
    claim.content match {
      case userClaim: UserClaim =>
        checkUnlockedDatasetAccess(claim, userClaim, datasetId) match {
          case Some(errorRoute) => errorRoute
          case None =>
            getEncryptionId(userClaim.roles).fold(unauthorizedRoute) { encryptionKeyId =>
              f(userClaim.id, encryptionKeyId)
            }
        }
      case _ => unauthorizedRoute
    }

  def getUserInfo(claim: Jwt.Claim) =
    claim.content match {
      case UserClaim(userId, roles, _, _) =>
        getEncryptionId(roles)
          .map { encryptionKeyId =>
            (encryptionKeyId, userId)
          }

      case _ => None
    }

  private def getEncryptionId(roles: List[Jwt.Role]) =
    roles.flatMap {
      case OrganizationRole(organizationRoleId, _, Some(encryptionKeyId), _, _) =>
        organizationRoleId.toEither match {
          case Left(_) => Some(encryptionKeyId)

          case Right(Wildcard) => None

          case _ => None
        }

      case _ => None
    }.headOption

  // TODO remove fold after confirming that clients are sending a datasetId
  def checkDatasetAccess(claim: Jwt.Claim, maybeDatasetId: Option[DatasetId]): Boolean =
    maybeDatasetId.fold(true) { datasetId =>
      Validator.hasDatasetAccess(claim, datasetId, DatasetPermission.CreateDeleteFiles)
    }

  // TODO: same deal with datasetId's vs. wildcards as above ^
  def checkUnlockedDatasetAccess(
    claim: Jwt.Claim,
    userClaim: UserClaim,
    maybeDatasetId: Option[DatasetId]
  ): Option[Route] =
    maybeDatasetId.fold[Option[Route]](None) { datasetId =>
      if (Validator.hasDatasetAccess(claim, datasetId, DatasetPermission.CreateDeleteFiles)) {
        if (userClaim.roles.exists {
            case DatasetRole(id, _, _, locked) => {
              if (id == Wildcard.inject[Role.RoleIdentifier[DatasetId]]) {
                true
              } else if (id == datasetId.inject[Role.RoleIdentifier[DatasetId]]) {
                locked.forall(!_)
              } else {
                false
              }
            }
            case _ => false
          }) {
          None
        } else {
          Some(lockedRoute)
        }
      } else {
        Some(unauthorizedRoute)
      }
    }

}
