// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload

import java.io.ByteArrayInputStream
import java.time.{ ZoneOffset, ZonedDateTime }
import java.util
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongUnaryOperator

import akka.actor.{ ActorSystem, Scheduler }
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.{ ConnectionPoolSettings, ServerSettings }
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.impl.MultipartUpload
import akka.stream.scaladsl.{ Flow, Keep, RestartSource, Sink, Source, SourceQueueWithComplete }
import akka.stream.{ ActorMaterializer, OverflowStrategy, QueueOfferResult, ThrottleMode }
import akka.{ Done, NotUsed }
import cats.data.EitherT
import cats.implicits._
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.handlers.AsyncHandler
import com.amazonaws.regions.AwsRegionProvider
import com.amazonaws.services.dynamodbv2.model.{
  AttributeValue,
  BatchWriteItemRequest,
  BatchWriteItemResult,
  GetItemRequest,
  PutItemRequest,
  PutRequest,
  QueryRequest,
  WriteRequest
}
import com.amazonaws.services.dynamodbv2.{ AmazonDynamoDBAsync, AmazonDynamoDBAsyncClientBuilder }
import com.amazonaws.services.s3.model.PartListing
import com.amazonaws.services.s3.model.transform.Unmarshallers.ListPartsResultUnmarshaller
import com.pennsieve.service.utilities.ContextLogger
import com.blackfynn.upload.ChunkPorts.{ CacheHash, SendChunk }
import com.blackfynn.upload.CompletePorts.{ apply => _, unapply => _, _ }
import com.blackfynn.upload.HashPorts.GetChunkHashes
import com.blackfynn.upload.PreviewPorts.{ CachePreview, InitiateUpload }
import com.blackfynn.upload.UploadPorts._
import com.blackfynn.upload.alpakka.S3Requests.multipartUploadUnmarshaller
import com.blackfynn.upload.clients.AwsAsyncCallback.async
import com.blackfynn.upload.clients.DynamoHelpers._
import com.blackfynn.upload.clients.DynamoItemNotFound
import com.blackfynn.upload.model.Eventual.EventualTConverter
import com.blackfynn.upload.model.{ ChunkHash, PackagePreview, PackagePreviewMetadata, PreviewFile }
import com.blackfynn.upload.routing.{ TooManyRequests, UploadRouting }
import com.typesafe.config.{ Config, ConfigFactory }
import io.circe.syntax.EncoderOps

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

trait LoadMonitor {
  def canReserve(size: Long): Boolean
  def decrement(size: Long): Long
}

object MemoryLoadMonitor {
  val MEMORY_CEILING: Long = {
    val rt = Runtime.getRuntime()
    val freeMemory: Double = (rt.maxMemory - (rt.totalMemory - rt.freeMemory)).toDouble
    (freeMemory * 0.9).toLong
  }
}

/**
  * Monitor memory on on the service, allowing or denying a memory allocation.
  * A pattern like this is needed, since we cannot read a request entity fully
  * without knowing if there is enough memory ahead of time. Doing so without
  * checking might result in an out of memory error.
  *
  * The usage pattern is as follows:
  *
  *   (1) Call `loadMonitor.canReserve(MAX_CHUNK_SIZE)` with a large (pessimistic)
  *       memory size, the largest chunk size allowable.
  *
  *   (2) If it succeeds, read the entity body.
  *
  *   (3) Call `loadMonitor.decrement()` with `MAX_CHUNK_SIZE - entityBodySize`.
  *       This will free the excess memory requested in (1).
  *
  *   (4) Process the entity body.
  *
  *   (5) Call `loadMonitor.decrement()` with `entityBodySize`.
  *       This will free the actual memory needed to read the entity body
  *
  * Usage:
  *
  * <code>
  * if (loadMonitor.canReserve(MAX_CHUNK_SIZE)) {
  *   // Read request entity body
  *   loadMonitor.decrement(MAX_CHUNK_SIZE - entityBody.size)
  *   // Process entity body
  *   loadMonitor.decrement(entityBody.size)
  * } else {
  *   // Can't allocate
  *   throw new Exception("Too much memory allocated for existing chunks. Free some first")
  * }
  * </code>
  */
class MemoryLoadMonitor extends LoadMonitor {

  val allocatedMemory: AtomicLong = new AtomicLong(0)

  /**
    * Called prior to a memory allocation operation. This ensures that the requested amount of memory to allocate
    * exists prior to the allocation.
    *
    * @param size
    * @return
    */
  override def canReserve(size: Long): Boolean = {
    val previousMemory: Long = allocatedMemory.get()
    val newMemory: Long = allocatedMemory.updateAndGet(new LongUnaryOperator() {
      def applyAsLong(currentMemory: Long): Long = {
        val proposedMemory = currentMemory + size
        if (proposedMemory < MemoryLoadMonitor.MEMORY_CEILING) {
          proposedMemory
        } else {
          currentMemory
        }
      }
    })

    val allow: Boolean = newMemory > previousMemory
    allow
  }

  override def decrement(size: Long): Long = {
    val newMemory: Long = allocatedMemory.updateAndGet(new LongUnaryOperator() {
      def applyAsLong(currentMemory: Long): Long = {
        Math.max(0, currentMemory - size)
      }
    })
    newMemory
  }
}

object Server extends App {
  lazy val rawConfig: Config = ConfigFactory.load()
  lazy val config: UploadConfig = UploadConfig(
    host = rawConfig.getString("host"),
    port = rawConfig.getInt("port"),
    jwtKey = rawConfig.getString("jwt-key"),
    preview = PreviewConfig(
      parallelism = rawConfig.getInt("preview.parallelism"),
      batchWriteParallelism = rawConfig.getInt("preview.batch-write-parallelism"),
      batchWriteMaxRetries = rawConfig.getInt("preview.batch-write-max-retries")
    ),
    complete = CompleteConfig(parallelism = rawConfig.getInt("complete.parallelism")),
    status = StatusConfig(listPartsParallelism = rawConfig.getInt("status.list-parts-parallelism")),
    s3 = S3Config(
      bucket = rawConfig.getString("s-3.bucket"),
      region = rawConfig.getString("s-3.region"),
      connectionPoolQueueSize = rawConfig.getInt("s-3.connection-pool-queue-size")
    ),
    apiBaseUrl = rawConfig.getString("api-base-url"),
    chunkHashTableName = rawConfig.getString("chunk-hash-table-name"),
    previewMetadataTableName = rawConfig.getString("preview-metadata-table-name"),
    previewFilesTableName = rawConfig.getString("preview-files-table-name"),
    maxRequestsPerSecond = rawConfig.getInt("akka.http.host-connection-pool.max-open-requests") * 3,
    maxChunkSize = rawConfig.getBytes("akka.http.server.parsing.max-content-length")
  )

  implicit val actorSystem: ActorSystem = ActorSystem("UploadService")
  implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()
  implicit val scheduler: Scheduler = actorSystem.scheduler
  implicit val ec: ExecutionContext = actorSystem.dispatcher
  implicit val log: ContextLogger = new ContextLogger()
  private val credentialsProvider = DefaultAWSCredentialsProviderChain.getInstance()
  private val awsRegionProvider: AwsRegionProvider =
    new AwsRegionProvider {
      override def getRegion: String = config.s3.awsRegion.getName
    }

  implicit val s3Settings: S3Settings =
    S3Settings(actorSystem.settings.config)
      .copy(credentialsProvider = credentialsProvider, s3RegionProvider = awsRegionProvider)

  private val dynamoClient: AmazonDynamoDBAsync = AmazonDynamoDBAsyncClientBuilder.defaultClient()

  private val http = Http()

  private def s3SingleDispatcher(request: HttpRequest): Future[HttpResponse] =
    http.singleRequest(request)

  private val s3HttpsConnectionPool =
    http.cachedHostConnectionPoolHttps[Promise[HttpResponse]](
      f"${config.s3.bucket}.s3.amazonaws.com"
    )

  // Use HTTPS connection pooling for short-lived S3 operations like preview, complete.
  // We set the queue size for outgoing requests to be quite large in order
  // to accommodate the situation where a preview request contains 1000+ files.
  //
  // Using akka-stream parallel computation operations like mapUnorderAsync()
  // is not sufficient to limit parallelism. See https://www.gregbeech.com/2018/04/08/akka-http-client-pooling-and-parallelism/
  //
  // This approach is adapted from:
  // (1) https://kazuhiro.github.io/scala/akka/akka-http/akka-streams/2016/01/31/connection-pooling-with-akka-http-and-source-queue.html
  // (2) https://doc.akka.io/docs/akka-http/current/client-side/host-level.html
  val s3HttpsQueue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = Source
    .queue[(HttpRequest, Promise[HttpResponse])](
      config.s3.connectionPoolQueueSize,
      OverflowStrategy.backpressure
    )
    .via(s3HttpsConnectionPool)
    .toMat(Sink.foreach({
      case ((Success(resp), p)) => p.success(resp)
      case ((Failure(e), p)) => p.failure(e)
    }))(Keep.left)
    .run

  // Note: this is intended for short-lived requests to AWS
  private def s3PooledDispatcher(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    s3HttpsQueue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued => responsePromise.future
      case QueueOfferResult.Dropped =>
        Future.failed(TooManyRequests)
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed =>
        Future.failed(
          new RuntimeException(
            "Queue was closed (pool shut down) while running the request. Try again later."
          )
        )
    }
  }

  private val cachePreview: CachePreview =
    preview => {
      // cache metadata
      val putMetadata = {
        val metadata = new util.HashMap[String, AttributeValue](2)
        metadata.put("importId", new AttributeValue(preview.metadata.importId.toString))
        metadata.put("metadata", new AttributeValue(preview.metadata.asJson.noSpaces))

        async(dynamoClient.putItemAsync)(
          new PutItemRequest(config.previewMetadataTableName, withOneWeekTimeToExist(metadata))
        )
      }

      // cache all files
      val putFileRequests = preview.files.map { file =>
        val item = new util.HashMap[String, AttributeValue](3)
        item.put("importId", new AttributeValue(preview.metadata.importId.toString))
        item.put("fileNameHash", new AttributeValue(file.fileNameHash))
        item.put("file", new AttributeValue(file.asJson.noSpaces))

        new WriteRequest(new PutRequest(withOneWeekTimeToExist(item)))
      }
      val batchWriteRequests = {
        // dynamo batch requests cannot contain more than 25 elements
        putFileRequests.grouped(25).map { putFileRequestGroup =>
          val batchRequest = new util.HashMap[String, util.List[WriteRequest]]()
          batchRequest.put(config.previewFilesTableName, putFileRequestGroup.asJava)
          new BatchWriteItemRequest(batchRequest)
        }
      }
      val putFiles = EitherT {
        Source(batchWriteRequests.toList)
          .mapAsyncUnordered(config.preview.batchWriteParallelism)(
            request =>
              batchWriteItemRetries(
                async(
                  (
                    req: BatchWriteItemRequest,
                    asyncHandler: AsyncHandler[BatchWriteItemRequest, BatchWriteItemResult]
                  ) => dynamoClient.batchWriteItemAsync(req, asyncHandler)
                ),
                request,
                config.preview.batchWriteMaxRetries
              ).value
          )
          .runWith(Sink.seq)
          .map(_.toList.sequence)
      }

      for {
        _ <- putMetadata
        _ <- putFiles
      } yield ()
    }

  private val initiateUpload: InitiateUpload =
    request =>
      EitherT {
        s3PooledDispatcher(request)
          .flatMap {
            case HttpResponse(status, _, entity, _) if status.isSuccess() =>
              Unmarshal(entity)
                .to[MultipartUpload]
                .map(_.asRight[Throwable])
                .recover {
                  case e => e.asLeft
                }

            case HttpResponse(status, _, entity, _) =>
              Unmarshal(entity).to[String].map { err =>
                log.noContext.error(
                  s"InitiateUpload failed for ${request.uri} S3 Response status $status error $err"
                )
                new S3Exception(err).asLeft
              }
          }
          .recover {
            case NonFatal(e) => e.asLeft
          }
      }

  // By instantiating this singleRequest it should force the instantiation of a separate pool
  // which prevents chunk requests from getting caught behind other requests made by upload service.
  // Additionally it will not allow retries for requests that cannot be retried
  private val noRetriesSettings = ConnectionPoolSettings(actorSystem).withMaxRetries(0)

  private val sendChunkClient: HttpRequest => Future[HttpResponse] =
    request => http.singleRequest(request, settings = noRetriesSettings)

  private val sendChunk: SendChunk =
    request =>
      EitherT {
        sendChunkClient(request)
          .flatMap { response =>
            response match {
              case response @ HttpResponse(status, _, entity, _) if status.isSuccess() =>
                Unmarshal(entity)
                  .to[String]
                  .map { _ =>
                    log.noContext.info(
                      s"UploadChunk send chunk ${request.uri} S3 Response status ${response.status}"
                    )
                    ().asRight[Throwable]
                  }
                  .recover {
                    case e => e.asLeft
                  }

              case HttpResponse(_, _, entity, _) =>
                Unmarshal(entity)
                  .to[String]
                  .map { err =>
                    log.noContext.error(
                      s"UploadChunk failed to send chunk ${request.uri} S3 Response status ${response.status} error $err"
                    )
                    new S3Exception(err).asLeft
                  }
            }
          }
      }

  private val listParts: ListParts =
    request =>
      EitherT {
        s3PooledDispatcher(request)
          .flatMap { response =>
            log.noContext.info(
              s"ListParts parts request ${request.uri} response status ${response.status}"
            )
            response match {
              case HttpResponse(status, _, entity, _) if status.isSuccess() =>
                entity.dataBytes
                  .runWith(Sink.seq)
                  .map(
                    byteStrings => new ByteArrayInputStream(byteStrings.flatMap(_.toSeq).toArray)
                  )
                  .map { inputStream =>
                    Try(new ListPartsResultUnmarshaller().unmarshall(inputStream)).toEither
                      .map(_.some)
                  }

              case HttpResponse(NotFound, _, _, _) =>
                Future(Option.empty[PartListing].asRight[Throwable])

              case HttpResponse(_, _, entity, _) =>
                Unmarshal(entity).to[String].map { err =>
                  log.noContext
                    .error(s"ListParts parts request ${request.uri} failed with error $err")
                  new S3Exception(err).asLeft
                }
            }
          }
          .recover {
            case NonFatal(e) => e.asLeft
          }
      }

  private val completeUpload: CompleteUpload =
    request =>
      EitherT {
        s3PooledDispatcher(request)
          .flatMap { response =>
            log.noContext.info(
              s"CompleteUpload complete request ${request.uri} response status ${response.status}"
            )
            response match {
              case HttpResponse(status, _, entity, _) if status.isSuccess() =>
                Unmarshal(entity)
                  .to[String]
                  .map { _ =>
                    ().asRight[Throwable]
                  }
                  .recover {
                    case e => e.asLeft
                  }

              case HttpResponse(_, _, entity, _) =>
                Unmarshal(entity)
                  .to[String]
                  .map { err =>
                    log.noContext.error(s"CompleteUpload failed with $err")
                    new S3Exception(err).asLeft
                  }
            }
          }
      }

  private val getPreview: GetPreview =
    importId => {
      val getMetadata = {
        val id = Map("importId" -> new AttributeValue(importId.toString)).asJava
        async(dynamoClient.getItemAsync)(new GetItemRequest(config.previewMetadataTableName, id))
          .subflatMap(getItemStringProperty[PackagePreviewMetadata]("metadata"))
      }
      val getFiles = {
        val request = new QueryRequest(config.previewFilesTableName)
          .withKeyConditionExpression(s"importId = :v_id")
          .withExpressionAttributeValues(
            Map(":v_id" -> new AttributeValue(importId.toString)).asJava
          )
        queryAllResults(async(dynamoClient.queryAsync), request)
          .subflatMap(_.traverse(getQueryStringProperty[PreviewFile]("file")))
          .map(_.flatten)
      }

      for {
        metadata <- getMetadata
        files <- getFiles
      } yield PackagePreview(metadata, files)
    } leftMap {
      case DynamoItemNotFound =>
        MissingPreview(s"No previews found for importId: $importId"): Throwable
      case e =>
        log.noContext.error(s"GetPreview for importId $importId failed with ${e.getMessage}")
        e
    }

  private val sendComplete: SendComplete =
    request => s3SingleDispatcher(request).toEventual

  private val checkFileExists: CheckFileExists =
    request =>
      EitherT {
        s3PooledDispatcher(request)
          .flatMap {
            case HttpResponse(status, _, entity, _) if status.isSuccess() =>
              entity.discardBytes()
              Future.successful(true.asRight[Throwable])

            case HttpResponse(NotFound, _, entity, _) =>
              entity.discardBytes()
              Future.successful(false.asRight[Throwable])

            case HttpResponse(_, _, entity, _) =>
              Unmarshal(entity)
                .to[String]
                .map(err => new S3Exception(err).asLeft)
          }
      }

  private val cacheHash: CacheHash =
    chunkHash => {
      val item: java.util.Map[String, AttributeValue] = {
        val item = new util.HashMap[String, AttributeValue](3)

        item.put("id", new AttributeValue(chunkHash.id.value))
        item.put("hash", new AttributeValue(chunkHash.hash))
        item.put("importId", new AttributeValue(chunkHash.importId.toString))

        val aWeekAhead =
          ZonedDateTime
            .now(ZoneOffset.UTC)
            .plusWeeks(1L)
            .toInstant
            .toEpochMilli
            .toString

        item.put("TimeToExist", new AttributeValue(aWeekAhead))

        item
      }

      async(dynamoClient.putItemAsync)(new PutItemRequest(config.chunkHashTableName, item))
        .map(_ => ())
    }

  private val getChunkHashes: GetChunkHashes =
    (hashIds, importId) =>
      Source(hashIds.toList)
        .mapAsync(100) { hashId =>
          val id =
            Map(
              "id" -> new AttributeValue(hashId.value),
              "importId" -> new AttributeValue(importId.toString)
            ).asJava

          async(dynamoClient.getItemAsync)(new GetItemRequest(config.chunkHashTableName, id))
            .subflatMap(getItemStringPropertyAsString("hash"))
            .leftMap {
              case DynamoItemNotFound =>
                MissingPreview(s"No hash found for id: $id"): Throwable
              case e =>
                log.noContext.error(s"GetChunkHash for hashId $hashId failed with ${e.getMessage}")
                e
            }
            .map(hash => ChunkHash(hashId, hashId.importId, hash))
            .value
        }

  implicit val loadMonitor: LoadMonitor = new MemoryLoadMonitor()

  val ports: UploadPorts = {
    UploadPorts(
      ChunkPorts(sendChunk, cacheHash),
      PreviewPorts(cachePreview, initiateUpload),
      CompletePorts(listParts, completeUpload, getPreview, sendComplete, checkFileExists),
      HashPorts(getChunkHashes, getPreview)
    )
  }

  val settings: ServerSettings = ServerSettings(actorSystem)

  val serverSource: Source[IncomingConnection, Future[Http.ServerBinding]] =
    Http().bind(config.host, config.port, settings = settings)

  val handler: Flow[HttpRequest, HttpResponse, NotUsed] = UploadRouting(config, ports)

  log.noContext.info(s"settings.maxConnections : ${settings.maxConnections}")
  log.noContext.info(s"settings.maxConnections : ${settings.maxConnections}")
  log.noContext.info(
    s"config.preview = <parallelism: ${config.preview.parallelism}, batchWriteParallelism: ${config.preview.batchWriteParallelism}, batchWriteMaxRetries: ${config.preview.batchWriteMaxRetries}>"
  )
  log.noContext.info(s"config.complete = <parallelism: ${config.complete.parallelism}>")
  log.noContext.info(
    s"config.status = <listPartsParallelism: ${config.status.listPartsParallelism}>"
  )

  // https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/
  val serve: Future[Done] = RestartSource
    .withBackoff(minBackoff = 100.milliseconds, maxBackoff = 2.seconds, randomFactor = 0) { () =>
      serverSource
        .throttle(
          elements = config.maxRequestsPerSecond,
          per = 1.second,
          maximumBurst = config.maxRequestsPerSecond * 30,
          mode = ThrottleMode.Shaping
        )
        // Taken from akka's Http.bindAndHandle() implementation:
        .mapAsyncUnordered(settings.maxConnections) { connection: IncomingConnection =>
          connection.handleWith { handler.mapMaterializedValue(_ => Future.successful(Done)) }
        }
    }
    .runWith(Sink.ignore)

  log.noContext.info(s"Server online at http://${config.host}:${config.port}")

  Await.result(serve, Duration.Inf)
}
