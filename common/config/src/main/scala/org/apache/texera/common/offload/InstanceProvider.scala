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

/** Raised when an instance cannot be rented, or cannot be made usable. */
class InstanceProvisioningException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

/**
  * A request to rent one instance for one operator.
  *
  * @param instanceType which instance to rent
  * @param operatorId   the operator that will run on it, for naming and tracing
  * @param executionId  the execution this rental belongs to
  * @param image        image to run instead of the provider's configured default,
  *                     letting one operator bring its own tooling. None keeps the
  *                     platform default, which is what every workflow saved before
  *                     this existed carries.
  */
case class InstanceRequest(
    instanceType: InstanceType,
    operatorId: String,
    executionId: Long,
    image: Option[String] = None
) {
  require(operatorId != null && operatorId.trim.nonEmpty, "operatorId must be non-blank")
  // These check the string as stored, not a trimmed copy of it, because the stored
  // string is what reaches `docker run`. Validating `image.trim` while passing
  // `image` would let " ghcr.io/acme/qc:1 " through every guard here and fail as
  // `invalid reference format` only after an instance had been rented.
  require(image.forall(_.nonEmpty), "image must be non-blank when supplied")
  require(
    image.forall(i => i == i.trim),
    "image must have no surrounding whitespace; normalise it before renting"
  )
  // The image is positional in `docker run`, so one starting with `-` is parsed as
  // a flag and the launcher script after it is taken for the image instead. The
  // panel rejects this at compile time; this is the boundary that actually builds
  // the command, and it refuses rather than emitting one that means something else.
  require(
    image.forall(!_.startsWith("-")),
    "image must not start with '-'; it is a positional docker argument"
  )
}

/**
  * One operator's resolved rental: what to rent for it, and what to run on it.
  *
  * A single value rather than parallel collections keyed by operator id. The
  * rental loop's money-safety invariant is "everything rented gets released", and
  * two collections that must be kept in step by hand are exactly how a later edit
  * desyncs them.
  *
  * @param operatorId   the operator this rental is for
  * @param instanceType the instance resolved for it
  * @param image        image it declared, or None for the platform default
  */
case class OffloadRental(
    operatorId: String,
    instanceType: InstanceType,
    image: Option[String] = None
) {
  require(operatorId != null && operatorId.trim.nonEmpty, "operatorId must be non-blank")
}

/**
  * A rented instance.
  *
  * @param instanceId   provider-assigned identifier, used to release it
  * @param instanceType what was rented
  * @param nodeAddress  the instance's Pekko address once it has joined the
  *                     cluster; None while it has not
  * @param providerName which provider rented it
  */
case class RentedInstance(
    instanceId: String,
    instanceType: InstanceType,
    nodeAddress: Option[String],
    providerName: String
) {
  require(instanceId != null && instanceId.trim.nonEmpty, "instanceId must be non-blank")

  def hasJoined: Boolean = nodeAddress.isDefined
}

/**
  * Rents instances for offloaded operators.
  *
  * Implementations back this with different compute: a memory-capped local
  * container for development, an EC2 virtual machine for real offloading.
  */
trait InstanceProvider {

  /** Identifier for this backend, matching offload.provider in offload.conf. */
  def name: String

  /**
    * Rents an instance and waits for it to become usable.
    *
    * @throws InstanceProvisioningException if the instance cannot be started
    */
  def acquire(request: InstanceRequest): RentedInstance

  /**
    * Releases a rented instance.
    *
    * Must tolerate an instance that is already gone: release runs on failure
    * paths, where the instance may have died on its own.
    */
  def release(instance: RentedInstance): Unit
}
