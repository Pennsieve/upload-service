// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.model

import cats.data.EitherT

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import cats.implicits._

object Eventual {

  // In use primarily as we do not want to use EitherT unless a flatMap
  // is required between two Future[Either]. EitherT carries a 2x performance cost
  // it is unwise to use it if you do not need to flatMap or use another feature of EitherT

  type Eventual[A] = EitherT[Future, Throwable, A]

  implicit class EventualTConverter[A](future: Future[A]) {
    def toEventual(implicit ec: ExecutionContext): Eventual[A] =
      EitherT {
        future
          .map(_.asRight[Throwable])
          .recover {
            case NonFatal(e) => e.asLeft[A]
          }
      }
  }

  def eventual[A](a: A)(implicit ec: ExecutionContext): Eventual[A] =
    EitherT.rightT[Future, Throwable](a)

  def eventual[A](e: Throwable)(implicit ec: ExecutionContext): Eventual[A] =
    EitherT.leftT[Future, A](e)

  def successful[A](a: A): Eventual[A] = EitherT(Future.successful(a.asRight[Throwable]))

  def successful[A](ex: Throwable): Eventual[A] = EitherT(Future.successful(ex.asLeft[A]))

  def apply[A](either: Future[Either[Throwable, A]]): Eventual[A] =
    EitherT[Future, Throwable, A](either)
}
