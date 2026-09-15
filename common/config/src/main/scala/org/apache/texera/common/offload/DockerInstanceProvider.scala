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

import java.time.Duration
import scala.util.control.NonFatal

/**
  * Abstracts the Docker CLI so the provider's acquire/join/release logic can be
  * tested without a Docker daemon.
  */
trait DockerCli {

  /** Runs `docker <command...>` and returns stdout (trimmed). */
  def run(command: Seq[String]): String

  /** Removes (force) a container by id; must tolerate one already gone. */
  def remove(containerId: String): Unit

  /**
    * The Pekko addresses currently in the cluster.
    *
    * The provider diffs this before and after launching a container to learn the
    * address the new worker joined on, since workers bind ephemeral ports and so
    * cannot be addressed ahead of time.
    */
  def clusterMemberAddresses(): Set[String]
}

/**
  * Rents an instance by running the Texera worker image in a Docker container
  * with a hard `--memory` cgroup cap.
  *
  * The cgroup cap is what makes this a faithful stand-in for a real rented
  * instance: an under-sized container is OOM-killed by the kernel, for both JVM
  * heap growth and a Python UDF's native allocations. That is stronger than an
  * in-process `-Xmx`, which bounds only the JVM heap.
  *
  * Because a worker binds an ephemeral Pekko port, its address is not known in
  * advance. acquire launches the container, then watches cluster membership for
  * the single new address that appears -- that is this container's node.
  *
  * @param cli          the Docker CLI seam
  * @param image        worker image to run
  * @param seedAddress  host of the coordinator's seed node for the worker to join
  * @param joinTimeout  how long to wait for the container to join before giving up
  * @param pollInterval how often to re-check cluster membership
  * @param network      Docker network for the container: "host" to share the
  *                     host's namespace (both directions resolve over localhost),
  *                     or a user-defined bridge network's name
  * @param basePort     first Pekko port to assign; each concurrent worker gets its
  *                     own so the coordinator can reach it at a predictable
  *                     address. An ephemeral port cannot be published in advance.
  */
class DockerInstanceProvider(
    cli: DockerCli,
    image: String,
    seedAddress: String,
    joinTimeout: Duration,
    pollInterval: Duration,
    network: String,
    basePort: Int
) extends InstanceProvider {

  override def name: String = DockerInstanceProvider.ProviderName

  // Ports handed out so far this JVM. Rentals are sequential, but a released port
  // must not be reused while the old container is still shutting down, so this
  // only ever moves forward.
  private val nextPortOffset = new java.util.concurrent.atomic.AtomicInteger(0)

  private def allocatePort(): Int = basePort + nextPortOffset.getAndIncrement()

  override def acquire(request: InstanceRequest): RentedInstance = {
    // Snapshot membership first so we can tell which address is new afterwards.
    val before = cli.clusterMemberAddresses()

    val workerPort = allocatePort()
    val containerId =
      try {
        cli.run(buildRunCommand(request, workerPort)).trim
      } catch {
        case NonFatal(t) =>
          throw new InstanceProvisioningException(
            s"Failed to start a container for operator '${request.operatorId}': ${t.getMessage}",
            t
          )
      }

    try {
      val address = waitForJoin(before, request)
      RentedInstance(
        instanceId = containerId,
        instanceType = request.instanceType,
        nodeAddress = Some(address),
        providerName = name
      )
    } catch {
      // Deliberately Throwable, not NonFatal: NonFatal excludes
      // InterruptedException, and waitForJoin blocks in Thread.sleep and an
      // Await for up to the join timeout. An interrupt there (pool shutdown,
      // request cancellation) would otherwise skip removal and leave a running,
      // billing container with no record of it anywhere.
      case t: Throwable =>
        // The container is running but unusable; tear it down so it does not
        // linger. Removal is done once here, before deciding how to report, so
        // neither branch can forget it. waitForJoin can also raise
        // non-provisioning errors (a failing membership read), wrapped for context.
        safeRemove(containerId)
        t match {
          case e: InstanceProvisioningException => throw e
          case other =>
            throw new InstanceProvisioningException(
              s"Container $containerId for operator '${request.operatorId}' did not become " +
                s"usable: ${other.getMessage}",
              other
            )
        }
    }
  }

  override def release(instance: RentedInstance): Unit = safeRemove(instance.instanceId)

  /**
    * Builds the `docker run` argument list for a worker container.
    *
    * @param workerPort Pekko port the worker binds and advertises. Fixed rather
    *                   than ephemeral so the coordinator can reach it: on a bridge
    *                   network it is published to the host, and on the host
    *                   network it must not collide with the coordinator's 2552.
    */
  private[offload] def buildRunCommand(request: InstanceRequest, workerPort: Int): Seq[String] = {
    val hostNetwork = network == DockerInstanceProvider.HostNetwork

    // On the host network the container shares the host's stack, so both
    // directions are localhost and there is nothing to publish. On a bridge
    // network the worker's port must be published, and each side has to advertise
    // a name the other can route to.
    val networkArgs =
      if (hostNetwork) Seq("--network", DockerInstanceProvider.HostNetwork)
      else Seq("--network", network, "-p", s"$workerPort:$workerPort")

    val advertisedHost = if (hostNetwork) "localhost" else containerName(request)

    val nameArgs =
      if (hostNetwork) Seq.empty
      // A stable name doubles as the DNS alias peers on this network resolve.
      else Seq("--name", containerName(request))

    Seq(
      "run",
      "-d", // detached: acquire watches membership rather than blocking on the worker
      "--rm", // self-remove on exit so a crashed worker leaves no dead container
      s"--memory=${request.instanceType.memoryBytes}",
      // OOM behaviour must be a kill, not swap paging that would mask under-sizing.
      "--memory-swap=" + request.instanceType.memoryBytes
    ) ++ networkArgs ++ nameArgs ++ Seq(
      "--label",
      s"texera.offload.operator=${request.operatorId}",
      "--label",
      s"texera.offload.execution=${request.executionId}",
      "--label",
      s"texera.offload.instanceType=${request.instanceType.name}",
      // Marks the worker as dedicated to this one operator, so it declares the
      // Pekko role that keeps general round-robin placement off it. Without this
      // another operator's workers could land inside this container's memory cap.
      "-e",
      s"${OffloadWorkerLauncher.DedicatedNodeEnv}=1",
      // What this worker tells the coordinator to dial it at, and the port it
      // binds. Without these it would advertise the host's public IP (the image's
      // default discovery asks an external echo service), which the coordinator
      // cannot route back to.
      "-e",
      s"${OffloadWorkerLauncher.AdvertisedHostnameEnv}=$advertisedHost",
      "-e",
      s"${OffloadWorkerLauncher.AdvertisedPortEnv}=$workerPort",
      // An operator may bring its own image, so long as that image still launches
      // the worker below; the configured one is the default for everything else.
      request.image.getOrElse(image),
      OffloadWorkerLauncher.WorkerLauncherScript,
      OffloadWorkerLauncher.SeedAddressFlag,
      seedAddress
    )
  }

  /** Stable, DNS-safe container name; also the alias peers resolve on a bridge. */
  private[offload] def containerName(request: InstanceRequest): String = {
    val sanitized = request.operatorId.replaceAll("[^A-Za-z0-9_.-]", "-")
    s"texera-offload-${request.executionId}-$sanitized"
  }

  /**
    * Polls cluster membership until one new address appears, or the timeout
    * elapses. Returns the new address.
    */
  private def waitForJoin(before: Set[String], request: InstanceRequest): String = {
    val deadline = System.nanoTime() + joinTimeout.toNanos
    val sleepMs = math.max(1L, pollInterval.toMillis)

    while (System.nanoTime() < deadline) {
      val newcomers = cli.clusterMemberAddresses() -- before
      newcomers.toList match {
        case single :: Nil => return single
        case Nil           => Thread.sleep(sleepMs)
        case many          =>
          // More than one new member means we cannot attribute an address to
          // this container. Renting sequentially is what avoids this; hitting it
          // is a bug, not a transient state, so fail rather than guess.
          throw new InstanceProvisioningException(
            s"Ambiguous cluster join for operator '${request.operatorId}': multiple new " +
              s"nodes appeared (${many.mkString(", ")}). Instances must be rented one at a time."
          )
      }
    }
    throw new InstanceProvisioningException(
      s"Container for operator '${request.operatorId}' did not join the cluster within " +
        s"${joinTimeout.toSeconds}s."
    )
  }

  private def safeRemove(containerId: String): Unit =
    try cli.remove(containerId)
    catch {
      // The container may already be gone (it self-removes with --rm on crash).
      // A release must never fail for that reason: OffloadRentalPlan.releaseAll
      // relies on release being safe to call on an already-dead instance.
      case NonFatal(_) => ()
    }
}

object DockerInstanceProvider {

  /** Matches the `offload.provider` value in offload.conf. */
  val ProviderName: String = "docker"

  /** Docker's built-in host-namespace network. */
  val HostNetwork: String = "host"
}
