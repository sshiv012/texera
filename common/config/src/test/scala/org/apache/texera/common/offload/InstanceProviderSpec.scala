/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.common.offload

import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/**
  * Tests the provisioning contract shared by every backend (local container,
  * EC2), using a recording fake.
  *
  * The invariant under test is cost safety: a rented instance is billed until it
  * is released, so every path that acquires one must also release it -- including
  * the paths where joining the cluster times out or the caller's work throws.
  */
class InstanceProviderSpec extends AnyFlatSpec {

  private val catalog = InstanceCatalog(
    Seq(
      InstanceType("local-2g", 2, 2.0, 0.0),
      InstanceType("local-8g", 4, 8.0, 0.0)
    )
  )

  /**
    * Records every acquire/release so a test can assert nothing was leaked.
    *
    * @param failToJoin  simulate an instance that never joins the cluster
    * @param failToStart simulate a provider that cannot start the instance
    */
  private class FakeProvider(
      failToJoin: Boolean = false,
      failToStart: Boolean = false
  ) extends InstanceProvider {
    val acquired: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val released: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    var nextId = 0

    override def name: String = "fake"

    override def acquire(request: InstanceRequest): RentedInstance = {
      if (failToStart) throw new InstanceProvisioningException("cannot start instance")
      nextId += 1
      val id = s"fake-$nextId"
      acquired += id
      RentedInstance(
        instanceId = id,
        instanceType = request.instanceType,
        nodeAddress = if (failToJoin) None else Some(s"pekko://Amber@10.0.0.$nextId:2552"),
        providerName = name
      )
    }

    override def release(instance: RentedInstance): Unit = released += instance.instanceId
  }

  private def request(typeName: String = "local-2g"): InstanceRequest =
    InstanceRequest(
      instanceType = catalog.byName(typeName).get,
      operatorId = "MyOperator-1",
      executionId = 42L
    )

  // ---------------------------------------------------------------------------
  // RentedInstance / InstanceRequest validation
  // ---------------------------------------------------------------------------

  "InstanceRequest" should "reject a blank operator id" in {
    assertThrows[IllegalArgumentException](
      InstanceRequest(catalog.byName("local-2g").get, "  ", 1L)
    )
  }

  it should "reject a blank image, which must be normalised to None before here" in {
    assertThrows[IllegalArgumentException](
      InstanceRequest(catalog.byName("local-2g").get, "Op-1", 1L, image = Some("  "))
    )
  }

  // The stored string is what reaches `docker run`, so it is the string that gets
  // validated. An untrimmed one would otherwise pass and fail as `invalid
  // reference format` only after an instance had been rented.
  it should "reject an untrimmed image rather than renting and failing on docker" in {
    assertThrows[IllegalArgumentException](
      InstanceRequest(
        catalog.byName("local-2g").get,
        "Op-1",
        1L,
        image = Some(" ghcr.io/acme/qc:1 ")
      )
    )
  }

  // `docker run` reads its image positionally, so an image starting with `-` is
  // taken for a flag and the launcher script after it for the image. Refuse to
  // build that command rather than emit one that means something else.
  it should "reject an image that would be parsed as a docker flag" in {
    assertThrows[IllegalArgumentException](
      InstanceRequest(catalog.byName("local-2g").get, "Op-1", 1L, image = Some("--privileged"))
    )
  }

  it should "accept an ordinary registry reference" in {
    val request =
      InstanceRequest(catalog.byName("local-2g").get, "Op-1", 1L, image = Some("ghcr.io/acme/qc:1"))
    assert(request.image.contains("ghcr.io/acme/qc:1"))
  }

  "RentedInstance" should "reject a blank instance id" in {
    assertThrows[IllegalArgumentException](
      RentedInstance("", catalog.byName("local-2g").get, None, "fake")
    )
  }

  it should "report whether it has joined the cluster" in {
    val joined =
      RentedInstance("i-1", catalog.byName("local-2g").get, Some("pekko://a@h:1"), "fake")
    val notJoined = RentedInstance("i-2", catalog.byName("local-2g").get, None, "fake")
    assert(joined.hasJoined)
    assert(!notJoined.hasJoined)
  }

  // ---------------------------------------------------------------------------
  // The acquire / release contract every backend must honour.
  //
  // The rent-many/release-many bracket lives in OffloadRentalPlan (and is
  // covered by OffloadRentalPlanSpec); these pin the single-instance contract
  // the providers implement.
  // ---------------------------------------------------------------------------

  "acquire" should "return an instance carrying the address it joined on" in {
    val p = new FakeProvider()
    val instance = p.acquire(request())
    assert(instance.hasJoined)
    assert(instance.nodeAddress.contains("pekko://Amber@10.0.0.1:2552"))
    assert(instance.instanceType.name == "local-2g")
  }

  it should "raise InstanceProvisioningException when the instance cannot be started" in {
    val p = new FakeProvider(failToStart = true)
    assertThrows[InstanceProvisioningException](p.acquire(request()))
    assert(p.acquired.isEmpty)
  }

  it should "report a non-joined instance rather than pretending it is usable" in {
    // A provider that cannot resolve an address must say so; callers refuse to
    // pin an operator to an instance with no address.
    val instance = new FakeProvider(failToJoin = true).acquire(request())
    assert(!instance.hasJoined)
  }

  "release" should "release the instance it is given" in {
    val p = new FakeProvider()
    val instance = p.acquire(request())
    p.release(instance)
    assert(p.released.toList == List(instance.instanceId))
  }
}
