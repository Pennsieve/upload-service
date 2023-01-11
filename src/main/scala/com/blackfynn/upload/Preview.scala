// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import akka.stream.Materializer
import akka.stream.alpakka.s3.{ MetaHeaders, S3Headers, S3Settings }
import akka.stream.alpakka.s3.headers.{ CannedAcl, ServerSideEncryption }
import com.blackfynn.upload.alpakka.S3Location
//import akka.stream.alpakka.s3.acl.CannedAcl
//import akka.stream.alpakka.s3.impl.ServerSideEncryption.AES256
// import akka.stream.alpakka.s3.impl._
import akka.stream.scaladsl.{ Sink, Source }
import cats.data.EitherT
import cats.implicits._
import com.blackfynn.upload.alpakka.MultipartUpload
import com.pennsieve.auth.middleware.UserId
import com.pennsieve.models.FileTypeGrouping
import com.pennsieve.models.Utilities.cleanS3Key
import com.blackfynn.upload.alpakka.S3Requests.initiateMultipartUploadRequest
import com.blackfynn.upload.alpakka.Signer.createSignedRequestT
import com.blackfynn.upload.model.Eventual.Eventual
import com.blackfynn.upload.model._

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }

object Preview {

  def apply(
    userId: UserId,
    previewRequest: PreviewRequest,
    append: Boolean,
    destinationCollection: Option[CollectionId]
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: PreviewPorts,
    config: UploadConfig,
    settings: S3Settings
  ): Eventual[List[PackagePreview]] = {

    for {
      collectionToFiles <- {
        EitherT.fromEither[Future] {
          val (uploadCollections, fileUploads) = Preview.getUploads(previewRequest.files)
          fileUploads
            .groupBy(_.parent)
            .map {
              case (parent, files) =>
                parent.fold((Option.empty[List[CollectionUpload]], files).asRight[Exception]) {
                  parent =>
                    val maybeAncestors =
                      getAncestors(uploadCollections, parent.id)
                        .map {
                          _.map { collection =>
                            collection.parentId match {
                              case None => collection.copy(parentId = destinationCollection)
                              case Some(_) => collection
                            }
                          }
                        }

                    maybeAncestors.map(ancestors => (Some(ancestors), files))
                }
            }
            .toList
            .sequence
        }
      }

      previews <- {
        Preview
          .groupFilesByCollection(collectionToFiles, append)
          .map(_.toEitherT[Future])
          .sequence
      }

      previewsWithMultipartId <- {
        previews.flatten.map { preview =>
          EitherT {
            Source(preview.files)
              .mapAsyncUnordered(config.preview.parallelism) { file =>
                val initiatedUploadResponse: Eventual[MultipartUpload] =
                  initiateUpload(
                    UploadUri(userId, preview.metadata.importId, cleanS3Key(file.fileName)),
                    ServerSideEncryption.aes256(),
                    file.chunkedUpload
                      .map(_.chunkSize) // TODO: don't need to store this after uploads-consumer stops reading
                  )

                initiatedUploadResponse.map { multipart =>
                  file.copy(multipartUploadId = Some(MultipartUploadId(multipart.uploadId)))
                }.value
              }
              .runWith(Sink.seq)
              .map { maybeFilesWithMultipart =>
                maybeFilesWithMultipart.toList.sequence
                  .map(filesWithMultipart => preview.copy(files = filesWithMultipart))
              }
          }
        }.sequence
      }

      _ <- previewsWithMultipartId.map(ports.cachePreview).sequence
    } yield previewsWithMultipartId
  }

  private def initiateUpload(
    uri: UploadUri,
    sse: ServerSideEncryption,
    chunkSize: Option[Long]
  )(implicit
    ec: ExecutionContext,
    mat: Materializer,
    ports: PreviewPorts,
    config: UploadConfig,
    settings: S3Settings
  ): Eventual[MultipartUpload] =
    for {
      signedReq <- {
        val metadata =
          chunkSize.map(cSize => MetaHeaders(Map[String, String]("chunksize" -> cSize.toString)))

        val headers =
          metadata match {
            case Some(metaheaders) =>
              S3Headers()
                .withServerSideEncryption(sse)
                .withMetaHeaders(metaheaders)
                .withCannedAcl(CannedAcl.Private)
            case None => S3Headers().withServerSideEncryption(sse).withCannedAcl(CannedAcl.Private)
          }

        createSignedRequestT {
          initiateMultipartUploadRequest(S3Location(config.s3.bucket, uri.toString), headers)
        }
      }

      maybeMultipartUpload <- ports.initiateUpload(signedReq)
    } yield maybeMultipartUpload

  /*
   * Used to group files to-be upload to S3 into a list of packages that they
   * will be used to construct.
   *
   * Files are grouped based on their parent collection, name and filetype (depending on how they are
   * defined in FileTypeInfo).
   *
   * Annotation files are handled uniquely, appended to the groups they need to be.
   *
   */
  private def groupFilesByCollection(
    uploads: List[(Option[List[CollectionUpload]], List[FileUpload])],
    append: Boolean
  ): List[Either[Throwable, List[PackagePreview]]] =
    uploads.map {
      case (collections, files) =>
        val (annotations, nonAnnotations) =
          files.partition(_.info.grouping == FileTypeGrouping.Annotation)

        val (remainingAnnotations, dataFiles) =
          attachAnnotations(nonAnnotations, annotations)

        val groupMap = dataFiles.foldLeft(Map.empty[String, List[FileUpload]]) {
          case (map, file) =>
            val key: String = file.info.grouping match {
              case FileTypeGrouping.ByType =>
                file.fileType.toString.toLowerCase // groups based solely on FileType
              case FileTypeGrouping.ByTypeByName =>
                file.baseName + "." + file.fileType.toString.toLowerCase // groups based on the files' names and types
              case _ =>
                java.util.UUID.randomUUID.toString // unique string to group the rest of the files individually
            }

            val files = map.getOrElse(key, List.empty[FileUpload])
            val updatedFiles =
              if (file.isMasterFile) file :: files
              else files :+ file

            map.updated(key, updatedFiles)
        }

        val groups = groupMap.values.toList
        val annotationGroups = groups match {
          // Handles the case when there are only annotation files being uploaded
          case Nil => groupAnnotations(annotations, append)

          // Handles the case when there are regular (non-annotation) files being uploaded
          case _ => groupAnnotations(remainingAnnotations, append)
        }

        val result = groups ++ annotationGroups

        val maybeAncestors = collections.map(_.reverse.tail.reverse)
        val maybeParent = collections.flatMap(_.lastOption)
        result.traverse(PackagePreview.fromFiles(maybeParent, maybeAncestors))
    }

  /*
   * Annotations are grouped as one upload if append is set
   * Otherwise, they are uploaded individually
   */
  private def groupAnnotations(
    annotations: List[FileUpload],
    append: Boolean
  ): List[List[FileUpload]] =
    annotations match {
      case head :: tail if append => List(List(head.copy(annotations = tail)))
      case original => original.grouped(1).toList
    }

  /*
   * Annotations are attached to files with the same basename.
   * Any annotations not attached to a file are return as a list of leftover annotation files.
   */
  private def attachAnnotations(
    files: List[FileUpload],
    annotations: List[FileUpload]
  ): (List[FileUpload], List[FileUpload]) = {
    val annotationsByName = annotations.groupBy(_.baseName)

    val (leftoverAnnotations, updatedFiles) = files
      .foldLeft((annotationsByName, List.empty[FileUpload])) {
        case ((leftover, accumulator), file) =>
          val related = leftover.getOrElse(
            file.baseName,
            List
              .empty[FileUpload]
          )
          val attached = file.copy(annotations = related)

          (leftover - file.baseName, attached :: accumulator)
      }

    (leftoverAnnotations.values.toList.flatten, updatedFiles)
  }

  type CollectionNameParentNameAndDepth =
    Map[(String, String, Int), CollectionUpload]

  private def getUploads(files: List[UserFile]): (List[CollectionUpload], List[FileUpload]) = {
    val collections: CollectionNameParentNameAndDepth = Map()
    val uploads = List[FileUpload]()
    val sortedFiles: Vector[UserFile] =
      files.sortBy(_.filePath.map(_.segments)).reverse.distinct.toVector

    val (collectionMap, fileUploads): (CollectionNameParentNameAndDepth, List[FileUpload]) =
      sortedFiles.foldLeft((collections, uploads)) {
        case (acc, file) => accumulateFiles(acc, file)
      }

    (collectionMap.values.toList, fileUploads)
  }

  private def getParent(
    collectionMap: CollectionNameParentNameAndDepth,
    name: String,
    path: String,
    depth: Int
  ): Option[CollectionUpload] =
    collectionMap.get((name, path, depth))

  private def collectionSeen(
    collectionMap: CollectionNameParentNameAndDepth,
    name: String,
    path: String,
    depth: Int
  ): Boolean =
    getParent(collectionMap, name, path, depth).isDefined

  private def makePath(folderArray: List[String], depth: Int): String =
    folderArray.take(depth).mkString("/")

  private def accumulateFiles(
    _acc: (CollectionNameParentNameAndDepth, List[FileUpload]),
    file: UserFile
  ): (CollectionNameParentNameAndDepth, List[FileUpload]) = {

    val names = file.filePath
      .fold(List(file.fileName))(_.segments.filter(n => n != "") :+ file.fileName)

    names.indices.foldLeft(_acc) {
      case (acc: (CollectionNameParentNameAndDepth, List[FileUpload]), depth: Int) =>
        val collectionMap: CollectionNameParentNameAndDepth = acc._1
        val uploads = acc._2
        val isRoot = depth == 0 // the first name is the root collection
        val isCollection = depth < (names.size - 1) // every name except the last name must be a collection

        // the parent collection immediately precedes the current name
        val parent =
          isRoot match {
            case true => None
            case false =>
              if (depth > 1) {
                getParent(collectionMap, names(depth - 1), makePath(names, depth - 1), depth - 1)
              } else
                getParent(collectionMap, names(depth - 1), makePath(names, depth - 1), depth - 1)
          }

        val seen =
          collectionSeen(collectionMap, names(depth), makePath(names, depth), depth)

        isCollection match {
          case true =>
            if (seen) acc
            else {
              val newCollection =
                CollectionUpload(
                  id = CollectionId(),
                  name = names(depth),
                  parentId = parent.map(_.id),
                  depth = depth
                )
              val key = (names(depth), makePath(names, depth), depth)

              (collectionMap + (key -> newCollection), uploads)
            }
          case false =>
            val newUpload =
              FileUpload(names(depth), file.uploadId, file.size, file.filePath, parent, Some(depth))
            (collectionMap, newUpload :: uploads)
        }
    }
  }

  @tailrec
  private def getAncestors(
    collections: List[CollectionUpload],
    parentId: CollectionId,
    ancestors: List[CollectionUpload] = List.empty
  ): Either[Exception, List[CollectionUpload]] =
    collections.find(_.id == parentId) match {
      case None => ParentNotPresentException.asLeft

      case Some(parent @ CollectionUpload(_, _, None, _)) => (parent :: ancestors).asRight

      case Some(parent @ CollectionUpload(_, _, Some(ancestorId), _)) =>
        getAncestors(collections, ancestorId, parent :: ancestors)
    }

  object ParentNotPresentException
      extends Exception("No parent with specified id could be found in ancestors")
}

case class MissingPreview(val message: String) extends Exception(message)
