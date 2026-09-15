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

import scala.collection.mutable
import scala.util.control.NonFatal

/**
  * What renting produced: the live instances, and each operator's node address.
  *
  * @param instances  every rented instance, for later release
  * @param addresses  operator id -> the Pekko address its instance joined on
  */
case class OffloadRentalResult(
    instances: Seq[RentedInstance],
    addresses: Map[String, String]
)

/**
  * Rents and releases the instances for a set of offloaded operators, keeping
  * the money-safety invariant: nothing that was rented is left un-released.
  *
  * Provider-agnostic on purpose -- it drives any [[InstanceProvider]], so the
  * bookkeeping is tested against a fake without a cluster.
  *
  * @param provider    the backend that actually rents (Docker, EC2, ...)
  * @param executionId the execution these rentals belong to, for labelling
  * @param beforeRelease invoked for every instance that has a node address, just
  *                      before it is released. The caller uses this to announce
  *                      the impending cluster departure; releasing a joined
  *                      instance without announcing it looks like a node crash to
  *                      the cluster listener. It is a hook rather than a direct
  *                      call because common/config cannot depend on amber, and it
  *                      lives here -- rather than at each call site -- so the
  *                      rollback path and the normal teardown path cannot diverge.
  *                      Deliberately has no default: a plan that silently skips
  *                      the announcement reintroduces the crash-misreporting bug,
  *                      so opting out has to be written out
  *                      ([[OffloadRentalPlan.NoAnnouncement]]).
  */
class OffloadRentalPlan(
    provider: InstanceProvider,
    executionId: Long,
    beforeRelease: RentedInstance => Unit
) {

  /**
    * Rents one instance per rental, in order.
    *
    * Renting is sequential because the Docker provider identifies a container's
    * node by the single new cluster member that appears after its launch;
    * overlapping launches would make that attribution ambiguous.
    *
    * If any rental fails, every instance already rented in this call is released
    * before the failure propagates, so a partial failure never leaves the user
    * paying for unused instances.
    */
  def rentAll(operators: Seq[OffloadRental]): OffloadRentalResult = {
    // One accumulator, holding the operator each instance was rented for. Two
    // parallel collections would have to be kept in step by hand, and the
    // money-safety invariant is "everything appended here gets released" -- so a
    // future early-exit in this loop must not be able to desync them.
    val rented = mutable.ListBuffer.empty[(String, RentedInstance)]
    // Addresses are accumulated alongside, so the result needs no `.get` on an
    // Option whose emptiness was ruled out several lines earlier -- an invariant
    // a later edit could break silently.
    val addresses = mutable.ListBuffer.empty[(String, String)]

    try {
      operators.foreach { rental =>
        val operatorId = rental.operatorId
        val instance = provider.acquire(
          InstanceRequest(rental.instanceType, operatorId, executionId, rental.image)
        )
        // Recorded before the address check: an instance with no address is
        // still running and billing, so it must be releasable if we throw.
        rented += operatorId -> instance
        instance.nodeAddress match {
          case Some(address) => addresses += operatorId -> address
          case None =>
            throw new InstanceProvisioningException(
              s"Instance ${instance.instanceId} for operator '$operatorId' reported no address."
            )
        }
      }
      OffloadRentalResult(rented.map(_._2).toList, addresses.toMap)
    } catch {
      // Deliberately Throwable, not NonFatal: NonFatal excludes
      // InterruptedException, and acquire can block for minutes waiting for a
      // container to join. An interrupt there would otherwise skip this rollback
      // and leave every instance rented so far running and billing.
      case t: Throwable =>
        releaseAll(rented.map(_._2).toList)
        throw t
    }
  }

  /**
    * Releases every given instance, best-effort.
    *
    * A failure releasing one instance must not strand the rest still billing, so
    * each is attempted and errors are swallowed here. (The provider already
    * tolerates an instance that is gone.)
    */
  def releaseAll(instances: Seq[RentedInstance]): Unit =
    instances.foreach { instance =>
      // Announce before tearing down, and only for instances that actually joined
      // -- one that never got an address was never a cluster member.
      if (instance.nodeAddress.isDefined) {
        try beforeRelease(instance)
        catch {
          case NonFatal(t) =>
            // An announcement failure must not stop the release; the instance
            // still has to go, even if its departure looks unexpected.
            OffloadRentalPlan.logger.warning(
              s"Failed to announce departure of ${instance.instanceId}: ${t.getMessage}"
            )
        }
      }
      // Throwable, not NonFatal: NonFatal excludes InterruptedException, and
      // release shells out to `docker rm` with no timeout. An interrupt during
      // one instance's removal would otherwise escape this foreach and leak every
      // remaining instance -- the same bug already fixed in rentAll and acquire.
      try provider.release(instance)
      catch {
        case t: Throwable =>
          // Swallowed so one stuck release cannot strand the others, but never
          // silently: a release that fails leaves a running, billing instance,
          // and this warning is the only record that it happened.
          OffloadRentalPlan.logger.warning(
            s"Failed to release offload instance ${instance.instanceId} " +
              s"(${instance.instanceType.name}) for execution $executionId; it may still be " +
              s"running and billing: ${t.getMessage}"
          )
      }
    }
}

object OffloadRentalPlan {
  // java.util.logging to match the module precedent (UserSystemConfig);
  // common/config has no scala-logging dependency.
  private val logger = java.util.logging.Logger.getLogger(classOf[OffloadRentalPlan].getName)

  /**
    * An explicit "no departure announcement needed" hook.
    *
    * For callers with no cluster to notify -- tests, and any future backend whose
    * instances are not cluster members. Naming it makes the opt-out visible at
    * the call site instead of hiding behind a defaulted parameter.
    */
  val NoAnnouncement: RentedInstance => Unit = _ => ()
}
