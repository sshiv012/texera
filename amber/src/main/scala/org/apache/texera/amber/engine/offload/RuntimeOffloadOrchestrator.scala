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

package org.apache.texera.amber.engine.offload

import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.ActorSystem
import org.apache.texera.amber.clustering.ClusterListener
import org.apache.texera.amber.core.workflow.PhysicalPlan
import org.apache.texera.amber.engine.common.AmberConfig
import org.apache.texera.amber.operator.LogicalOp
import org.apache.texera.common.compiler.OffloadPlanner
import org.apache.texera.common.config.OffloadConfigSettings
import org.apache.texera.common.offload.{
  DockerInstanceProvider,
  InstanceProvider,
  OffloadRental,
  OffloadRentalPlan,
  RentedInstance,
  ShellDockerCli
}

import java.time.Duration

/**
  * Runtime side of per-operator offloading: at execution start, rent an instance
  * for each offloaded operator, pin the plan to those instances, and hold the
  * rentals so they can be released when the execution ends.
  *
  * The provider and the placement resolution are injected, so the
  * rent-then-pin flow is tested without a Docker daemon or a live cluster.
  * [[RuntimeOffloadOrchestrator.forExecution]] wires the real Docker provider.
  *
  * @param provider    backend that rents instances
  * @param planner     resolves declarations and pins the plan
  * @param executionId execution these rentals belong to
  */
class RuntimeOffloadOrchestrator(
    provider: InstanceProvider,
    planner: OffloadPlanner,
    executionId: Long
) extends LazyLogging {

  // The announce hook lives in the rental plan so BOTH teardown paths use it:
  // release() at the end of an execution, and rentAll's rollback when a later
  // rental fails partway. An unannounced departure of a joined instance is read
  // as a node crash, which force-stops every non-completed execution.
  private val rentalPlan = new OffloadRentalPlan(
    provider,
    executionId,
    beforeRelease = instance => instance.nodeAddress.foreach(ClusterListener.expectDeparture)
  )

  // Held between prepare() and release() so the exact set rented is the set torn
  // down, even if the plan is later mutated.
  @volatile private var rented: Seq[RentedInstance] = Nil

  /**
    * Rents an instance per offloaded operator and returns the plan with those
    * operators pinned to their instances.
    *
    * On any failure, every instance rented in this call is released before the
    * error propagates, so a half-finished prepare never leaves paid instances
    * running. Returns the plan unchanged when nothing is offloaded.
    */
  def prepare(logicalOps: Seq[LogicalOp], physicalPlan: PhysicalPlan): PhysicalPlan = {
    val offloaded = planner.offloadedOperators(logicalOps)
    if (offloaded.isEmpty) return physicalPlan

    // validate() resolves the concrete instance (Manual today; Advised once the
    // memory advisor supplies an estimate). resolvedImage, not the raw field: a
    // cleared text box arrives as "" and means the platform default.
    val decisions = offloaded.map(op =>
      OffloadRental(op.operatorIdentifier.id, planner.validate(op), op.offload.resolvedImage)
    )

    logger.info(
      s"Offloading ${decisions.size} operator(s) for execution $executionId: " +
        decisions
          .map(rental =>
            s"${rental.operatorId} -> ${rental.instanceType.name}" +
              rental.image.map(image => s" on $image").getOrElse("")
          )
          .mkString(", ")
    )

    val result = rentalPlan.rentAll(decisions)
    rented = result.instances

    try {
      planner.pinPlan(physicalPlan, result.addresses)
    } catch {
      case t: Throwable =>
        // Pinning failed after renting; do not leave the instances running.
        release()
        throw t
    }
  }

  /** Releases every instance rented by [[prepare]]. Safe to call more than once. */
  def release(): Unit = {
    if (rented.nonEmpty) {
      logger.info(s"Releasing ${rented.size} offload instance(s) for execution $executionId")
      // releaseAll announces each departure via the beforeRelease hook above.
      rentalPlan.releaseAll(rented)
      rented = Nil
    }
  }
}

object RuntimeOffloadOrchestrator extends LazyLogging {

  /** True when offloading is enabled and at least one operator opts in. */
  def isNeeded(logicalOps: Seq[LogicalOp]): Boolean =
    OffloadConfigSettings.enabled && logicalOps.exists(_.isOffloaded)

  /**
    * Builds an orchestrator backed by the configured provider for one execution.
    *
    * Only the Docker provider is implemented; other configured values fail
    * loudly rather than silently doing nothing while the user waits for an
    * offload that never happens.
    *
    * @param actorSystem the master's actor system, used to read cluster
    *                    membership from the ClusterListener at /user/cluster-info
    * @param executionId the execution being prepared
    */
  def forExecution(actorSystem: ActorSystem, executionId: Long): RuntimeOffloadOrchestrator = {
    val provider = OffloadConfigSettings.provider match {
      case DockerInstanceProvider.ProviderName =>
        buildDockerProvider(actorSystem)
      case other =>
        throw new IllegalStateException(
          s"offload.provider='$other' is not implemented. Supported: " +
            s"${DockerInstanceProvider.ProviderName}."
        )
    }
    new RuntimeOffloadOrchestrator(provider, OffloadPlanner.fromConfig, executionId)
  }

  private def buildDockerProvider(actorSystem: ActorSystem): DockerInstanceProvider = {
    val cli = new ShellDockerCli(
      dockerBinary = OffloadConfigSettings.dockerBinary,
      memberAddresses = () =>
        ClusterListener.availableNodeAddresses(actorSystem).map(_.toString).toSet
    )
    // What the worker dials to reach the coordinator. On the host network the
    // container shares the host's stack, so the master's own address works. On a
    // bridge network "localhost" would be the container itself, so the configured
    // coordinator name is used instead.
    val seed =
      if (OffloadConfigSettings.usesHostNetwork)
        AmberConfig.masterNodeAddr.host.getOrElse("localhost")
      else OffloadConfigSettings.coordinatorAdvertisedHostname

    new DockerInstanceProvider(
      cli = cli,
      image = OffloadConfigSettings.dockerImage,
      seedAddress = seed,
      joinTimeout = Duration.ofSeconds(OffloadConfigSettings.joinTimeoutSeconds.toLong),
      pollInterval = Duration.ofMillis(OffloadConfigSettings.joinPollIntervalMs.toLong),
      network = OffloadConfigSettings.dockerNetwork,
      basePort = OffloadConfigSettings.workerPekkoBasePort
    )
  }
}
