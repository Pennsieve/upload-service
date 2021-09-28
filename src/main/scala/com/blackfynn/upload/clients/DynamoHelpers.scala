// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.clients

import java.time.{ ZoneOffset, ZonedDateTime }
import java.util

import akka.actor.Scheduler
import akka.pattern.after
import cats.data.EitherT
import cats.implicits._
import com.amazonaws.services.dynamodbv2.model.{
  AttributeValue,
  BatchWriteItemRequest,
  BatchWriteItemResult,
  GetItemResult,
  QueryRequest,
  QueryResult
}
import com.pennsieve.service.utilities.ContextLogger
import com.blackfynn.upload.model.Eventual.Eventual
import io.circe.Decoder
import io.circe.parser.decode

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

object DynamoHelpers {

  /** Add a TimeToExist property to a given item */
  def withOneWeekTimeToExist(
    item: util.HashMap[String, AttributeValue]
  ): util.HashMap[String, AttributeValue] = {
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

  /** Get the value of a string property from different response types,
    * avoiding NullPointerExceptions
    */
  def getItemStringProperty[A: Decoder](
    propertyName: String
  )(
    result: GetItemResult
  ): Either[Throwable, A] =
    Option(result.getItem) match {
      case None => Left(DynamoItemNotFound)
      case Some(item) => decodeFromItem(item.asScala, propertyName)
    }
  def getItemStringPropertyAsString(
    propertyName: String
  )(
    result: GetItemResult
  ): Either[Throwable, String] =
    Option(result.getItem) match {
      case None => Left(DynamoItemNotFound)
      case Some(item) => getStringFromItem(item.asScala, propertyName)
    }
  def getQueryStringProperty[A: Decoder](
    propertyName: String
  )(
    result: QueryResult
  ): Either[Throwable, List[A]] = {
    Option(result.getItems).map(_.asScala.toList) match {
      case None | Some(Nil) => Left(DynamoItemNotFound)
      case Some(items) => items.map(item => decodeFromItem(item.asScala, propertyName)).sequence
    }
  }

  private def getStringFromItem(
    item: mutable.Map[String, AttributeValue],
    propertyName: String
  ): Either[Throwable, String] =
    item.get(propertyName).flatMap(p => Option(p.getS)) match {
      case None => Left(DynamoPropertyNotFound(item, propertyName))
      case Some(property) => Right(property)
    }

  /** Decode the AttributeValue stored at with the 'propertyName' in the
    * given `item` map into the given type A. The AttributeValue for
    * this property is assumed to be a dynamo string type.
    */
  private def decodeFromItem[A: Decoder](
    item: mutable.Map[String, AttributeValue],
    propertyName: String
  ): Either[Throwable, A] =
    getStringFromItem(item, propertyName).flatMap(decode[A])

  /** Execute a given queryRequest with the given makeRequest function,
    * recursively paginating until there are no more results
    */
  def queryAllResults(
    makeRequest: QueryRequest => Eventual[QueryResult],
    request: QueryRequest
  )(implicit
    ec: ExecutionContext
  ): Eventual[List[QueryResult]] = {
    val start: (QueryRequest, List[QueryResult]) = (request, List.empty)

    start.tailRecM[Eventual, List[QueryResult]] {
      case (loopRequest, results) =>
        makeRequest(loopRequest).map { result =>
          val lastEvaluatedKey = Option(result.getLastEvaluatedKey)
          if (!lastEvaluatedKey.isDefined || lastEvaluatedKey.get.isEmpty) Right(result :: results)
          else {
            loopRequest.setExclusiveStartKey(lastEvaluatedKey.get)
            Left((loopRequest, result :: results))
          }
        }
    }
  }

  /** Execute batchWriteItem for the given batch request to upload all
    * items to dynamo.
    *
    * If there are unprocessed items in the response, retry uploading
    * those items until no unprocessed items remain.
    */
  def batchWriteItemRetries(
    makeRequest: BatchWriteItemRequest => Eventual[BatchWriteItemResult],
    request: BatchWriteItemRequest,
    maxRetries: Int
  )(implicit
    ec: ExecutionContext,
    scheduler: Scheduler,
    log: ContextLogger
  ): Eventual[Unit] = {
    val start = (request, 1)

    start.tailRecM[Eventual, Unit] {
      case (loopRequest, retryNum) if retryNum <= maxRetries =>
        makeRequest(loopRequest).flatMap { result =>
          val unprocessedItems = Option(result.getUnprocessedItems)
          if (!unprocessedItems.isDefined || unprocessedItems.get.isEmpty) EitherT.pure(Right(()))
          else {
            loopRequest.setRequestItems(unprocessedItems.get)

            // exponential backoff
            val delayMs = 500 * retryNum
            log.noContext.info(
              s"Found ${unprocessedItems.get.size} unprocessed items in BatchWriteItemResult, "
                + s"retrying in $delayMs milliseconds [${retryNum}/$maxRetries]"
            )
            EitherT.right(
              after(delayMs.milliseconds, scheduler)(
                Future.successful(Left((loopRequest, retryNum + 1)))
              )
            )
          }
        }
      case _ => EitherT.leftT(DynamoRetriesExceeded)
    }
  }
}

case object DynamoItemNotFound extends Exception("Item not found")
case class DynamoPropertyNotFound(item: mutable.Map[String, AttributeValue], property: String)
    extends Exception(s"Property '$property' not found in dynamo result: $item")
case object DynamoRetriesExceeded extends Exception("Retries exceeded")
