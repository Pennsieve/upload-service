// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.clients

import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.handlers.{ AsyncHandler => AWSAsyncHandler }
import com.blackfynn.upload.model.Eventual.Eventual
import java.util.concurrent.{ Future => JavaFuture }

import cats.data.EitherT

import scala.concurrent.Promise

class AwsAsyncCallback[Request <: AmazonWebServiceRequest, Result]
    extends AWSAsyncHandler[Request, Result] {

  val promise: Promise[Either[Exception, Result]] =
    Promise[Either[Exception, Result]]

  override def onError(exception: Exception): Unit =
    promise.success(Left(exception))

  override def onSuccess(request: Request, result: Result): Unit =
    promise.success(Right(result))

}

object AwsAsyncCallback {
  def async[Request <: AmazonWebServiceRequest, Result](
    f: (Request, AWSAsyncHandler[Request, Result]) => JavaFuture[Result]
  ): Request => Eventual[Result] =
    (request: Request) => {
      val awsAsyncCallback = new AwsAsyncCallback[Request, Result]
      f(request, awsAsyncCallback)
      EitherT(awsAsyncCallback.promise.future)
    }
}
