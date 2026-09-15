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
  * Tests the provider-agnostic rental bookkeeping: rent one instance per
  * offloaded operator, map each operator to its node address, and release every
  * instance exactly once -- including when a later rental fails.
  *
  * The engine-specific parts (which operators are offloaded, how a container is
  * run, how membership is read) are injected, so this pins the money-safety
  * invariants without a cluster.
  */
class OffloadRentalPlanSpec extends AnyFlatSpec {

  private def decision(name: String): InstanceType = InstanceType(name, 2, 4.0, 0.0416)

  /** A provider that hands out addresses and records acquire/release order. */
  private class RecordingProvider(
      failOnOperator: Option[String] = None
  ) extends InstanceProvider {
    val acquiredFor: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val requests: mutable.ListBuffer[InstanceRequest] = mutable.ListBuffer.empty
    val released: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    private var n = 0

    override def name: String = "recording"

    override def acquire(request: InstanceRequest): RentedInstance = {
      if (failOnOperator.contains(request.operatorId)) {
        throw new InstanceProvisioningException(s"boom for ${request.operatorId}")
      }
      n += 1
      acquiredFor += request.operatorId
      requests += request
      RentedInstance(s"inst-$n", request.instanceType, Some(s"pekko://Amber@10.0.0.$n:2552"), name)
    }

    override def release(instance: RentedInstance): Unit = released += instance.instanceId
  }

  private def rentalPlan(provider: InstanceProvider): OffloadRentalPlan =
    // These cases assert rent/release bookkeeping; the announcement behaviour has
    // its own section below.
    new OffloadRentalPlan(provider, executionId = 1L, OffloadRentalPlan.NoAnnouncement)

  // ---------------------------------------------------------------------------
  // Renting per operator (positive)
  // ---------------------------------------------------------------------------

  "rentAll" should "rent one instance per operator and map each to its address" in {
    val provider = new RecordingProvider()
    val result = rentalPlan(provider).rentAll(
      Seq(OffloadRental("Op-A", decision("t3.medium")), OffloadRental("Op-B", decision("m5.large")))
    )
    assert(result.addresses.keySet == Set("Op-A", "Op-B"))
    assert(result.addresses("Op-A") == "pekko://Amber@10.0.0.1:2552")
    assert(result.addresses("Op-B") == "pekko://Amber@10.0.0.2:2552")
    assert(provider.acquiredFor.toList == List("Op-A", "Op-B"))
  }

  it should "rent sequentially, in the given order" in {
    // Sequential is required: the Docker provider attributes the one new cluster
    // member to the container it just launched, which only holds if launches do
    // not overlap.
    val provider = new RecordingProvider()
    rentalPlan(provider).rentAll(
      Seq(
        OffloadRental("Op-A", decision("t3.medium")),
        OffloadRental("Op-B", decision("t3.medium")),
        OffloadRental("Op-C", decision("t3.medium"))
      )
    )
    assert(provider.acquiredFor.toList == List("Op-A", "Op-B", "Op-C"))
  }

  it should "return an empty result and rent nothing for no offloaded operators" in {
    val provider = new RecordingProvider()
    val result = rentalPlan(provider).rentAll(Seq.empty)
    assert(result.addresses.isEmpty)
    assert(result.instances.isEmpty)
    assert(provider.acquiredFor.isEmpty)
  }

  it should "carry the chosen instance type into the rental request" in {
    val provider = new RecordingProvider()
    val result = rentalPlan(provider).rentAll(Seq(OffloadRental("Op-A", decision("m5.large"))))
    assert(result.instances.head.instanceType.name == "m5.large")
  }

  it should "carry a per-operator image into the rental request, and none when unset" in {
    val provider = new RecordingProvider()
    rentalPlan(provider).rentAll(
      Seq(
        OffloadRental("Op-A", decision("m5.large"), Some("ghcr.io/acme/qc:1.0.0")),
        OffloadRental("Op-B", decision("m5.large"))
      )
    )
    assert(provider.requests.map(_.image).toList == List(Some("ghcr.io/acme/qc:1.0.0"), None))
  }

  // ---------------------------------------------------------------------------
  // Failure during renting -- release everything already rented (negative)
  // ---------------------------------------------------------------------------

  "rentAll" should "release already-rented instances when a later rental fails" in {
    // Op-A and Op-B succeed, Op-C fails: A and B must be released, or the user
    // is billed for two instances that will never be used.
    val provider = new RecordingProvider(failOnOperator = Some("Op-C"))
    val ex = intercept[InstanceProvisioningException] {
      rentalPlan(provider).rentAll(
        Seq(
          OffloadRental("Op-A", decision("t3.medium")),
          OffloadRental("Op-B", decision("t3.medium")),
          OffloadRental("Op-C", decision("t3.medium"))
        )
      )
    }
    assert(ex.getMessage.contains("Op-C"))
    assert(provider.released.toSet == Set("inst-1", "inst-2"))
  }

  it should "release nothing when the first rental fails" in {
    val provider = new RecordingProvider(failOnOperator = Some("Op-A"))
    assertThrows[InstanceProvisioningException] {
      rentalPlan(provider).rentAll(Seq(OffloadRental("Op-A", decision("t3.medium"))))
    }
    assert(provider.released.isEmpty)
  }

  it should "roll back when the rental is interrupted, not just on ordinary failures" in {
    // acquire can block for minutes waiting for a container to join, so an
    // interrupt (pool shutdown, request cancellation) is a realistic exit from
    // this loop. scala.util.control.NonFatal treats InterruptedException as
    // FATAL, so catching NonFatal here would skip the rollback entirely and leave
    // every instance rented so far running and billing.
    val provider = new RecordingProvider() {
      override def acquire(request: InstanceRequest): RentedInstance = {
        if (request.operatorId == "Op-B") throw new InterruptedException("pool shutdown")
        super.acquire(request)
      }
    }
    assertThrows[InterruptedException] {
      rentalPlan(provider).rentAll(
        Seq(
          OffloadRental("Op-A", decision("t3.medium")),
          OffloadRental("Op-B", decision("t3.medium"))
        )
      )
    }
    assert(provider.released.toList == List("inst-1"), "Op-A's instance must be released")
  }

  it should "release an instance that reported no address" in {
    // The instance is running and billing even though it never joined, so it has
    // to be recorded before the address check throws.
    val provider = new RecordingProvider() {
      override def acquire(request: InstanceRequest): RentedInstance = {
        val real = super.acquire(request)
        real.copy(nodeAddress = None)
      }
    }
    assertThrows[InstanceProvisioningException] {
      rentalPlan(provider).rentAll(Seq(OffloadRental("Op-A", decision("t3.medium"))))
    }
    assert(provider.released.toList == List("inst-1"))
  }

  // ---------------------------------------------------------------------------
  // Releasing (positive)
  // ---------------------------------------------------------------------------

  "releaseAll" should "release every rented instance" in {
    val provider = new RecordingProvider()
    val result = rentalPlan(provider).rentAll(
      Seq(
        OffloadRental("Op-A", decision("t3.medium")),
        OffloadRental("Op-B", decision("t3.medium"))
      )
    )
    rentalPlan(provider).releaseAll(result.instances)
    assert(provider.released.toSet == Set("inst-1", "inst-2"))
  }

  it should "release every instance even if one release throws" in {
    // A stuck release must not strand the others still billing.
    val provider = new RecordingProvider() {
      override def release(instance: RentedInstance): Unit = {
        if (instance.instanceId == "inst-1") throw new RuntimeException("stuck")
        super.release(instance)
      }
    }
    val result = rentalPlan(provider).rentAll(
      Seq(
        OffloadRental("Op-A", decision("t3.medium")),
        OffloadRental("Op-B", decision("t3.medium"))
      )
    )
    rentalPlan(provider).releaseAll(result.instances)
    assert(provider.released.contains("inst-2"))
  }

  it should "tolerate releasing an empty set" in {
    rentalPlan(new RecordingProvider()).releaseAll(Seq.empty)
  }

  // ---------------------------------------------------------------------------
  // beforeRelease -- announcing an impending cluster departure
  //
  // Releasing a joined instance without announcing it looks like a node crash to
  // the cluster listener, which force-stops every non-completed execution. Both
  // teardown paths must announce, so neither can regress independently.
  // ---------------------------------------------------------------------------

  private def announcingPlan(
      provider: InstanceProvider,
      announced: mutable.ListBuffer[String]
  ): OffloadRentalPlan =
    new OffloadRentalPlan(
      provider,
      executionId = 1L,
      beforeRelease = i => i.nodeAddress.foreach(announced += _)
    )

  "releaseAll" should "announce each joined instance before releasing it" in {
    val provider = new RecordingProvider()
    val announced = mutable.ListBuffer.empty[String]
    val plan = announcingPlan(provider, announced)
    val result = plan.rentAll(Seq(OffloadRental("Op-A", decision("t3.medium"))))
    plan.releaseAll(result.instances)
    assert(announced.toList == List("pekko://Amber@10.0.0.1:2552"))
  }

  "the rentAll rollback" should "also announce, not just the normal teardown path" in {
    // Op-A joins, Op-B fails; Op-A's live container is torn down by the rollback.
    // Without an announcement here the cluster reads that as a crash.
    val provider = new RecordingProvider(failOnOperator = Some("Op-B"))
    val announced = mutable.ListBuffer.empty[String]
    assertThrows[InstanceProvisioningException] {
      announcingPlan(provider, announced).rentAll(
        Seq(
          OffloadRental("Op-A", decision("t3.medium")),
          OffloadRental("Op-B", decision("t3.medium"))
        )
      )
    }
    assert(provider.released.toList == List("inst-1"))
    assert(announced.toList == List("pekko://Amber@10.0.0.1:2552"))
  }

  it should "not announce an instance that never got an address" in {
    // It was never a cluster member, so there is no departure to expect.
    val provider = new RecordingProvider() {
      override def acquire(request: InstanceRequest): RentedInstance =
        super.acquire(request).copy(nodeAddress = None)
    }
    val announced = mutable.ListBuffer.empty[String]
    assertThrows[InstanceProvisioningException] {
      announcingPlan(provider, announced).rentAll(Seq(OffloadRental("Op-A", decision("t3.medium"))))
    }
    assert(provider.released.toList == List("inst-1"), "it still has to be released")
    assert(announced.isEmpty)
  }

  it should "keep releasing the remaining instances when one release is interrupted" in {
    // release shells out to `docker rm` with no timeout, so an interrupt mid-loop
    // is realistic. NonFatal treats InterruptedException as FATAL, so catching
    // NonFatal here would escape the foreach and leak instances 2..n.
    val provider = new RecordingProvider() {
      override def release(instance: RentedInstance): Unit = {
        if (instance.instanceId == "inst-1") throw new InterruptedException("pool shutdown")
        super.release(instance)
      }
    }
    val plan = rentalPlan(provider)
    val result = plan.rentAll(
      Seq(
        OffloadRental("Op-A", decision("t3.medium")),
        OffloadRental("Op-B", decision("t3.medium"))
      )
    )
    plan.releaseAll(result.instances)
    assert(provider.released.toList == List("inst-2"), "inst-2 must still be released")
  }

  it should "still release when the announcement itself fails" in {
    val provider = new RecordingProvider()
    val plan = new OffloadRentalPlan(
      provider,
      executionId = 1L,
      beforeRelease = _ => throw new RuntimeException("listener unavailable")
    )
    val result = plan.rentAll(Seq(OffloadRental("Op-A", decision("t3.medium"))))
    plan.releaseAll(result.instances)
    assert(provider.released.toList == List("inst-1"))
  }
}
