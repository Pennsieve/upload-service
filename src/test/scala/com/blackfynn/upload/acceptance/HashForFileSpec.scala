// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance

import java.security.MessageDigest

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import cats.implicits._
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException
import com.blackfynn.service.utilities.ContextLogger
import com.blackfynn.upload.HashPorts.GetChunkHashes
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.StubRoutes._
import com.blackfynn.upload.TestAuthentication.{ addJWT, generateClaim, makeToken }
import com.blackfynn.upload.TestData._
import com.blackfynn.upload.UploadPorts
import com.blackfynn.upload.UploadPorts.GetPreview
import com.blackfynn.upload.model.{ ChunkHash, ChunkHashId, Eventual, FileHash, PackagePreview }

import io.circe.parser.decode
import org.scalatest.{ Matchers, WordSpec }

class HashForFileSpec extends WordSpec with Matchers with ScalatestRouteTest {
  implicit val log: ContextLogger = new ContextLogger()

  "Getting a hash for file" should {
    "return the hash for a upload with a single chunk" in {
      val ports = createHashPorts(successfulGetChunkHash)

      getHashRequest() ~> createRoutes(ports) ~> check {
        decode[FileHash](entityAs[String]) shouldBe Right(FileHash(FirstByteStringHash))
      }
    }

    "return the hash for a upload with a single chunk using a user claim" in {
      val ports = createHashPorts(successfulGetChunkHash)

      getHashRequest(userClaim = true) ~> createRoutes(ports) ~> check {
        decode[FileHash](entityAs[String]) shouldBe Right(FileHash(FirstByteStringHash))
      }
    }

    "return hashes for a upload with multiple chunks" in {
      val hashes = (0 until 2).map(createChunkHash).toList

      val successfulGetChunkHashes: GetChunkHashes =
        (requestedHashes, _) => {
          val onlyRequestedHashes = hashes.take(requestedHashes.length).map(Right(_))

          Source(onlyRequestedHashes)
        }

      val ports =
        createHashPorts(successfulGetChunkHashes, multipleChunksPreview)

      val digest = MessageDigest.getInstance("SHA-256")

      hashes.foreach(ch => digest.update(ch.hash.getBytes))

      getHashRequest() ~> createRoutes(ports) ~> check {
        decode[FileHash](entityAs[String]) shouldBe Right(FileHash(digest.digest()))
      }
    }

    "return bad request for invalid import id" in {
      val getHashInvalidImportId = addJWT(
        Get(s"/hash/id/invalid?fileName=$someZipName&userId=1"),
        token = makeToken(createClaim = generateClaim(userClaim = false))
      )
      getHashInvalidImportId ~> createRoutes(stubUploadPorts) ~> check(status shouldBe BadRequest)
    }

    "return bad request when using a service claim without a userId" in {
      val getHashInvalidImportId = addJWT(
        Get(s"/hash/id/$defaultImportId?fileName=$someZipName"),
        token = makeToken(createClaim = generateClaim(userClaim = false))
      )
      getHashInvalidImportId ~> createRoutes(stubUploadPorts) ~> check(status shouldBe BadRequest)
    }

    "return internal server error for failed attempt to get hash" in {
      val ports =
        createHashPorts(
          (_, _) => Source.single(createChunkHash(0)).map(_ => throw new Exception("shoot"))
        )

      getHashRequest() ~> createRoutes(ports) ~> check {
        status shouldBe InternalServerError
      }
    }

    "return not found when not all hashes can be found" in {
      val ports =
        createHashPorts(successfulGetChunkHash, multipleChunksPreview)

      getHashRequest() ~> createRoutes(ports) ~> check(status shouldBe NotFound)
    }
  }

  val successfulGetChunkHash: GetChunkHashes =
    (chunkHashIds, _) => {
      if (chunkHashIds.head.chunkNumber == 0 && chunkHashIds.length == 1)
        Source.single(Right(createChunkHash(0)))
      else {
        Source(
          Right(createChunkHash(0)) :: (1 until chunkHashIds.length)
            .map(_ => Left(new ResourceNotFoundException("That hash didn't exist")))
            .toList
        )
      }
    }

  val SecondByteStringHash: String =
    "1b68dcaa02a2314eccbc153e65c351112bbeba25012d324cfc340f511cebb4bd"

  private def createChunkHash(chunkNumber: Int) =
    ChunkHash(
      ChunkHashId(chunkNumber, defaultImportId, someZipName),
      defaultImportId,
      if (chunkNumber % 2 == 0) FirstByteStringHash else SecondByteStringHash
    )

  private val multipleChunksPreview =
    zipPackagePreview
      .copy(files = {
        zipPackagePreview.files
          .map { file =>
            file.copy(chunkedUpload = file.chunkedUpload.get.copy(totalChunks = 2).some)
          }
      })

  private def successfulGetPreview(preview: PackagePreview): GetPreview =
    _ => Eventual.successful(preview)

  private def createHashPorts(
    getChunkHashes: GetChunkHashes,
    preview: PackagePreview = zipPackagePreview
  ): UploadPorts =
    createPorts(getChunkHashes = getChunkHashes, hashGetPreview = successfulGetPreview(preview))

  def getHashRequest(userClaim: Boolean = false): HttpRequest =
    addJWT(
      Get(s"/hash/id/$defaultImportId?fileName=$someZipName&userId=1"),
      token = makeToken(createClaim = generateClaim(userClaim = userClaim))
    )

}
