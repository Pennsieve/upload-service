// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import java.io.File
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.http.scaladsl.server.Route
import akka.stream.alpakka.s3.S3Settings
import akka.stream.alpakka.s3.impl.{ MultipartUpload, S3Location }
import akka.util.ByteString
import com.amazonaws.services.s3.model.PartListing
import com.blackfynn.auth.middleware.Jwt.Role.RoleIdentifier
import com.blackfynn.auth.middleware.Jwt.{ DatasetRole, OrganizationRole }
import com.blackfynn.auth.middleware.{
  DatasetId,
  DatasetNodeId,
  EncryptionKeyId,
  Jwt,
  OrganizationId,
  OrganizationNodeId,
  ServiceClaim,
  UserClaim,
  UserId,
  Wildcard
}
import com.blackfynn.models.FileType.ZIP
import com.blackfynn.models.PackageType.Unsupported
import com.blackfynn.models.Utilities._
import com.blackfynn.models.{ NodeCodes, NodeId, Role }
import com.blackfynn.service.utilities.ContextLogger
import com.blackfynn.upload.ChunkPorts.{ CacheHash, SendChunk }
import com.blackfynn.upload.CompletePorts.{ apply => _, _ }
import com.blackfynn.upload.HashPorts.GetChunkHashes
import com.blackfynn.upload.PreviewPorts.{ CachePreview, InitiateUpload }
import com.blackfynn.upload.TestData.defaultChunkSize
import com.blackfynn.upload.UploadPorts._
import com.blackfynn.upload.model._
import com.blackfynn.upload.routing.UploadRouting
import shapeless.syntax.inject.InjectSyntax

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationDouble

class MockLoadMonitor extends LoadMonitor {
  var allow: Boolean = true
  def reset(): Unit = {
    allow = true
  }
  def disallow(): Unit = {
    allow = false
  }
  override def canReserve(size: Long): Boolean = allow
  override def decrement(size: Long): Long = 0
}

object TestData {

  val defaultOrganizationId: Int = 1
  val defaultUserId: Int = 1
  val defaultImportId: ImportId = ImportId(UUID.randomUUID())
  val anotherImportId: ImportId = ImportId(UUID.randomUUID())
  val defaultKeyId: String = "encryption-key"
  val defaultOrganizationNodeId: String = "node-id"
  val defaultDatasetNodeId: String = "dataset-node-id"
  val defaultDatasetId: Int = 1
  val defaultDestinationId: String = {
    NodeId("N", NodeCodes.collectionCode, UUID.randomUUID()).toString()
  }
  val defaultChunkSize: Long = 1000

  val defaultFileHash = FileHash("12345678")

  val tooMuchData: ByteString = ByteString.fromArray(Array.fill[Byte](20000)(9))

  val somePlusName: String = "some+.zip"

  val someZipName: String = "some^.zip"
  val escapedSomeZipName: String = escapeName(someZipName)
  val zipPackagePreview: PackagePreview =
    PackagePreview(
      PackagePreviewMetadata(
        packageName = "package+Name",
        escapedPackageName = "package%2BName",
        packageType = Unsupported,
        packageSubtype = "Compressed",
        fileType = ZIP,
        warnings = List.empty[String],
        groupSize = 100L,
        hasWorkflow = false,
        importId = defaultImportId,
        icon = "Zip",
        parent = None,
        ancestors = None,
        previewPath = None,
        escapedPreviewPath = None
      ),
      List(PreviewFile(1, someZipName, 100L, "multipart-upload-id", ChunkedUpload(100L, 1)))
    )

  val plusPackagePreview: PackagePreview =
    PackagePreview(
      PackagePreviewMetadata(
        packageName = "someName",
        escapedPackageName = "someName",
        packageType = Unsupported,
        packageSubtype = "Compressed",
        fileType = ZIP,
        warnings = List.empty[String],
        groupSize = 100L,
        hasWorkflow = false,
        importId = anotherImportId,
        icon = "Zip",
        parent = None,
        ancestors = None,
        previewPath = None,
        escapedPreviewPath = None
      ),
      List(PreviewFile(1, somePlusName, 100L, "multipart-upload-id", ChunkedUpload(100L, 1)))
    )

  val FirstByteStringHash: String =
    "9284ed4fd7fe1346904656f329db6cc49c0e7ae5b8279bff37f96bc6eb59baad"

  def createFile(name: String) = new File(s"src/test/resources/files/$name")
}

object StubPorts {
  implicit def s3Settings(implicit actorSystem: ActorSystem): S3Settings = S3Settings()

  val stubUploadConfig: UploadConfig =
    UploadConfig(
      host = "localhost",
      port = 80,
      jwtKey = "key",
      preview =
        PreviewConfig(parallelism = 100, batchWriteParallelism = 100, batchWriteMaxRetries = 10),
      complete = CompleteConfig(parallelism = 100),
      status = StatusConfig(listPartsParallelism = 100),
      s3 = S3Config("bucket", "us-east-1", 100),
      // This doesn't change anything in test see Server
      apiBaseUrl = "https://localhost",
      chunkHashTableName = "hash-table",
      previewMetadataTableName = "preview-metadata-table",
      previewFilesTableName = "preview-files-table",
      maxRequestsPerSecond = 10,
      maxChunkSize = defaultChunkSize // any payloads over this should fail
    )

  val doNothingCache: CachePreview = _ => Eventual.successful(())

  val successfulInitiateUpload: InitiateUpload =
    _ =>
      Eventual.successful {
        MultipartUpload(S3Location("bucket", "key"), "9d898281-642c-4ed5-af17-262dd3894b5a")
      }

  val listParts404: ListParts = _ => Eventual.successful(Option.empty[PartListing])

  val notImplementedSendChunk: SendChunk = _ => ???

  val notImplmentedCacheHash: CacheHash = _ => ???

  val notImplmentedGetChunkHash: GetChunkHashes = (_, _) => ???

  val notImplementedListParts: ListParts = _ => ???

  val notImplementedUploadComplete: CompleteUpload = _ => ???

  val notImplementedGetPreview: GetPreview = _ => ???

  val notImplementedSendComplete: SendComplete = _ => ???

  val notImplementedCheckFileExists: CheckFileExists = _ => ???

  val existsCheckFileExists: CheckFileExists = _ => Eventual.successful(true)

  val nonexistentCheckFileExists: CheckFileExists = _ => Eventual.successful(false)

  val stubUploadPorts: UploadPorts = createPorts()

  def createPorts(
    cachePreview: CachePreview = doNothingCache,
    initiateUpload: InitiateUpload = successfulInitiateUpload,
    sendChunk: SendChunk = notImplementedSendChunk,
    listParts: ListParts = notImplementedListParts,
    completeUpload: CompleteUpload = notImplementedUploadComplete,
    getPreview: GetPreview = notImplementedGetPreview,
    sendComplete: SendComplete = notImplementedSendComplete,
    checkFileExists: CheckFileExists = notImplementedCheckFileExists,
    cacheHash: CacheHash = notImplmentedCacheHash,
    getChunkHashes: GetChunkHashes = notImplmentedGetChunkHash,
    hashGetPreview: GetPreview = notImplementedGetPreview
  ): UploadPorts =
    UploadPorts(
      ChunkPorts(sendChunk, cacheHash),
      PreviewPorts(cachePreview, initiateUpload),
      CompletePorts(listParts, completeUpload, getPreview, sendComplete, checkFileExists),
      HashPorts(getChunkHashes, hashGetPreview)
    )
}

object StubRoutes {
  import StubPorts._

  implicit val loadMonitor = new MockLoadMonitor()

  def stubRoutes(implicit system: ActorSystem, log: ContextLogger): Route =
    createRoutes(stubUploadPorts)

  def createRoutes(ports: UploadPorts)(implicit system: ActorSystem, log: ContextLogger) =
    Route.seal(UploadRouting(stubUploadConfig, ports))
}

object TestAuthentication {
  import com.blackfynn.upload.StubPorts.stubUploadConfig
  import com.blackfynn.upload.TestData.{
    defaultDatasetNodeId,
    defaultKeyId,
    defaultOrganizationId,
    defaultOrganizationNodeId
  }
  type CreateClaim = (OrganizationId, Option[DatasetId]) => Jwt.Claim

  def generateWildcardClaim(organizationNodeId: OrganizationNodeId): CreateClaim =
    (_, _) =>
      Jwt.generateClaim(
        duration = 1 minute,
        content = UserClaim(
          id = UserId(1),
          roles = List(
            OrganizationRole(
              id = Wildcard.inject[RoleIdentifier[OrganizationId]],
              role = Role.Viewer,
              node_id = Some(organizationNodeId)
            )
          )
        )
      )

  def generateClaim(
    role: Role = Role.Editor,
    userClaim: Boolean = true,
    wildcardDataset: Boolean = false,
    datasetLocked: Option[Boolean] = None
  )(
    organizationId: OrganizationId,
    maybeDatasetId: Option[DatasetId]
  ): Jwt.Claim = {
    val organizationRole =
      OrganizationRole(
        organizationId.inject[RoleIdentifier[OrganizationId]],
        role,
        Some(EncryptionKeyId(defaultKeyId)),
        Some(OrganizationNodeId(defaultOrganizationNodeId))
      )

    val maybeDatasetRole =
      maybeDatasetId
        .fold(List.empty[Jwt.Role]) { datasetId =>
          List(
            DatasetRole(
              if (wildcardDataset) Wildcard.inject[RoleIdentifier[DatasetId]]
              else datasetId.inject[RoleIdentifier[DatasetId]],
              role,
              Some(DatasetNodeId(defaultDatasetNodeId)),
              datasetLocked
            )
          )
        }

    val roles = organizationRole :: maybeDatasetRole

    val claim = if (userClaim) UserClaim(UserId(1), roles) else ServiceClaim(roles)

    Jwt.generateClaim(duration = 1 minute, content = claim)
  }

  def makeToken(
    organizationId: OrganizationId = OrganizationId(defaultOrganizationId),
    createClaim: CreateClaim = generateClaim(),
    config: UploadConfig = stubUploadConfig,
    maybeDatasetId: Option[DatasetId] = None
  ): Jwt.Token =
    Jwt.generateToken(createClaim(organizationId, maybeDatasetId))(config.jwt)

  def addJWT(httpRequest: HttpRequest, token: Jwt.Token = makeToken()): HttpRequest =
    httpRequest.addHeader(Authorization(OAuth2BearerToken(token.value)))

  def createPost[T](
    token: Jwt.Token,
    entity: T,
    uri: String
  )(implicit
    m: ToEntityMarshaller[T],
    ec: ExecutionContext
  ): HttpRequest =
    addJWT(Post(uri, entity), token)
}
