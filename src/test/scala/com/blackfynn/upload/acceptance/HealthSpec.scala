// Copyright (c) 2018 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.upload.acceptance

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.pennsieve.service.utilities.ContextLogger
import com.blackfynn.upload.{ LoadMonitor, MockLoadMonitor }
import com.blackfynn.upload.StubPorts._
import com.blackfynn.upload.routing.UploadRouting
import org.scalatest.{ Matchers, WordSpecLike }

class HealthSpec extends WordSpecLike with Matchers with ScalatestRouteTest {
  implicit val log: ContextLogger = new ContextLogger()
  implicit val loadMonitor: LoadMonitor = new MockLoadMonitor()

  "/health" should {
    "return status OK" in {
      Get("/health") ~> route ~> check { status shouldBe OK }
    }
  }

  val route: Route = Route.seal(UploadRouting(stubUploadConfig, stubUploadPorts))
}
