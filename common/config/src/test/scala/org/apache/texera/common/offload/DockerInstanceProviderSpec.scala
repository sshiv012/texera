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
  * Tests DockerInstanceProvider with a fake Docker CLI and a controllable
  * cluster-membership view, so the acquire/join/release logic is exercised
  * without a Docker daemon.
  */
class DockerInstanceProviderSpec extends AnyFlatSpec {

  private val fourGiB = InstanceType("local-4g", 2, 4.0, 0.0)

  private def request(): InstanceRequest =
    InstanceRequest(instanceType = fourGiB, operatorId = "Op-1", executionId = 7L)

  /**
    * Records docker invocations and lets a test script membership so a launched
    * container "joins" after a set number of polls.
    *
    * @param runFails            simulate `docker run` failing
    * @param joinsAfterPolls     membership shows the new node after this many
    *                            polls; Int.MaxValue means it never joins
    * @param joinedAddressFor    address the container is deemed to have joined on
    */
  private class FakeDockerCli(
      runFails: Boolean = false,
      joinsAfterPolls: Int = 1,
      joinedAddress: String = "pekko://Amber@172.17.0.5:2552"
  ) extends DockerCli {
    val runCommands: mutable.ListBuffer[Seq[String]] = mutable.ListBuffer.empty
    val removed: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    private var pollsSinceRun = 0
    private var running = false

    override def run(command: Seq[String]): String = {
      if (runFails) throw new RuntimeException("docker: daemon not running")
      runCommands += command
      running = true
      pollsSinceRun = 0
      // `docker run -d` prints the container id.
      "container-abc123"
    }

    override def remove(containerId: String): Unit = {
      removed += containerId
      running = false
    }

    override def clusterMemberAddresses(): Set[String] = {
      // Before any run, only the coordinator is a member.
      val base = Set("pekko://Amber@10.0.0.1:2552")
      if (running) {
        pollsSinceRun += 1
        if (pollsSinceRun >= joinsAfterPolls) base + joinedAddress else base
      } else base
    }
  }

  private def provider(
      cli: DockerCli,
      network: String = DockerInstanceProvider.HostNetwork
  ): DockerInstanceProvider =
    new DockerInstanceProvider(
      cli = cli,
      image = "texera-computing-unit-worker:latest",
      seedAddress = "10.0.0.1",
      joinTimeout = java.time.Duration.ofSeconds(5),
      pollInterval = java.time.Duration.ofMillis(1),
      network = network,
      basePort = 2560
    )

  // ---------------------------------------------------------------------------
  // Provider identity
  // ---------------------------------------------------------------------------

  "DockerInstanceProvider" should "name itself to match the offload.provider value" in {
    assert(provider(new FakeDockerCli()).name == "docker")
  }

  // ---------------------------------------------------------------------------
  // Command construction
  // ---------------------------------------------------------------------------

  "the docker run command" should "cap memory at the instance size and pass the seed" in {
    val cli = new FakeDockerCli()
    provider(cli).acquire(request())
    val cmd = cli.runCommands.head
    assert(cmd.head == "run")
    assert(cmd.contains("-d"), "must be detached so acquire does not block on the worker")
    assert(cmd.contains("--rm"), "container must self-remove so a crash leaves nothing behind")
    // The cgroup memory cap is what makes an under-sized instance OOM-kill the
    // way a real rented instance would -- for JVM and Python alike.
    assert(cmd.exists(_ == s"--memory=${fourGiB.memoryBytes}"))
    assert(cmd.contains("texera-computing-unit-worker:latest"))
    // Seed address is passed through the worker launcher's flag.
    val flagIdx = cmd.indexOf(OffloadWorkerLauncher.SeedAddressFlag)
    assert(flagIdx > 0 && cmd(flagIdx + 1) == "10.0.0.1")
  }

  it should "label the container with the operator and execution for traceability" in {
    val cli = new FakeDockerCli()
    provider(cli).acquire(request())
    val cmd = cli.runCommands.head
    assert(cmd.exists(_.contains("Op-1")))
    assert(cmd.exists(_.contains("7")))
  }

  // ---------------------------------------------------------------------------
  // Networking: the worker and coordinator must be able to dial each other, and
  // the two supported topologies resolve that differently. A container's own
  // "localhost" is itself, so an unreachable advertised address means the worker
  // joins a cluster of one and the offload silently never happens.
  // ---------------------------------------------------------------------------

  "on the host network" should "share the host namespace and advertise localhost" in {
    val cli = new FakeDockerCli()
    provider(cli, network = "host").acquire(request())
    val cmd = cli.runCommands.head
    val netIdx = cmd.indexOf("--network")
    assert(netIdx >= 0 && cmd(netIdx + 1) == "host")
    // Nothing to publish: the container already shares the host's ports.
    assert(!cmd.contains("-p"))
    assert(cmd.contains(s"${OffloadWorkerLauncher.AdvertisedHostnameEnv}=localhost"))
  }

  it should "still pin a fixed port so the coordinator can predict the address" in {
    val cli = new FakeDockerCli()
    provider(cli, network = "host").acquire(request())
    val cmd = cli.runCommands.head
    assert(cmd.contains(s"${OffloadWorkerLauncher.AdvertisedPortEnv}=2560"))
  }

  "on a bridge network" should "publish the worker port and advertise the container name" in {
    val cli = new FakeDockerCli()
    val p = provider(cli, network = "texera-single-node")
    p.acquire(request())
    val cmd = cli.runCommands.head
    val netIdx = cmd.indexOf("--network")
    assert(netIdx >= 0 && cmd(netIdx + 1) == "texera-single-node")
    // Published so the coordinator (outside this network) can reach the worker.
    val pIdx = cmd.indexOf("-p")
    assert(pIdx >= 0 && cmd(pIdx + 1) == "2560:2560")
    // The container's DNS alias, not localhost, which would be the container.
    val expected = p.containerName(request())
    assert(cmd.contains(s"${OffloadWorkerLauncher.AdvertisedHostnameEnv}=$expected"))
    val nameIdx = cmd.indexOf("--name")
    assert(nameIdx >= 0 && cmd(nameIdx + 1) == expected)
  }

  it should "give each successively rented worker its own port" in {
    // Two workers sharing one fixed port would collide on bind and on publish.
    // Built directly rather than via acquire(), which would need the fake to
    // simulate a distinct cluster join per container.
    val p = provider(new FakeDockerCli(), network = "texera-single-node")
    val first = p.buildRunCommand(request(), workerPort = 2560)
    val second = p.buildRunCommand(InstanceRequest(fourGiB, "Op-2", 7L), workerPort = 2561)
    def published(cmd: Seq[String]): String = cmd(cmd.indexOf("-p") + 1)
    assert(published(first) == "2560:2560")
    assert(published(second) == "2561:2561")
    assert(published(first) != published(second))
  }

  it should "allocate a distinct port per acquire, upward from the base" in {
    // The counter only moves forward, so a port is not reused while the previous
    // container may still be shutting down.
    val cli = new FakeDockerCli(joinsAfterPolls = Int.MaxValue) // never joins; we only
    val p = provider(cli, network = "texera-single-node") //      want the run commands
    (1 to 2).foreach(_ => intercept[InstanceProvisioningException](p.acquire(request())))
    val ports = cli.runCommands.map(cmd => cmd(cmd.indexOf("-p") + 1)).toList
    assert(ports == List("2560:2560", "2561:2561"), s"got $ports")
  }

  "containerName" should "be DNS-safe so it works as a network alias" in {
    val p = provider(new FakeDockerCli())
    val name = p.containerName(InstanceRequest(fourGiB, "Filter_op#1 weird/id", 7L))
    assert(name.matches("[A-Za-z0-9_.-]+"), s"not DNS-safe: $name")
    assert(name.contains("7"))
  }

  // ---------------------------------------------------------------------------
  // Acquire waits for the container to join the cluster (positive)
  // ---------------------------------------------------------------------------

  "acquire" should "return the address the container joined on" in {
    val cli = new FakeDockerCli(joinsAfterPolls = 1)
    val rented = provider(cli).acquire(request())
    assert(rented.hasJoined)
    assert(rented.nodeAddress.contains("pekko://Amber@172.17.0.5:2552"))
    assert(rented.instanceId == "container-abc123")
  }

  it should "keep polling until a slow container joins" in {
    val cli = new FakeDockerCli(joinsAfterPolls = 4)
    val rented = provider(cli).acquire(request())
    assert(rented.hasJoined)
  }

  // ---------------------------------------------------------------------------
  // Acquire failure paths (negative) -- must never leak a container
  // ---------------------------------------------------------------------------

  it should "raise and not leak when docker run fails" in {
    val cli = new FakeDockerCli(runFails = true)
    assertThrows[InstanceProvisioningException](provider(cli).acquire(request()))
    assert(cli.removed.isEmpty, "nothing was started, so nothing to remove")
  }

  it should "remove the container and fail when it never joins before the timeout" in {
    val cli = new FakeDockerCli(joinsAfterPolls = Int.MaxValue)
    assertThrows[InstanceProvisioningException](provider(cli).acquire(request()))
    // The container was started, so it must be torn down or it bills forever.
    assert(cli.removed.contains("container-abc123"))
  }

  it should "remove the container when the join wait is interrupted" in {
    // waitForJoin blocks in Thread.sleep and a cluster-membership Await for up to
    // the join timeout, so an interrupt is a realistic exit. NonFatal treats
    // InterruptedException as FATAL, so catching NonFatal would skip removal and
    // leave a running, billing container with no record of it.
    val cli = new FakeDockerCli() {
      private var launched = false
      override def run(command: Seq[String]): String = {
        val id = super.run(command)
        launched = true
        id
      }
      // The pre-launch snapshot must succeed; only the polling after launch is
      // interrupted, which is where a real interrupt would land.
      override def clusterMemberAddresses(): Set[String] =
        if (launched) throw new InterruptedException("pool shutdown")
        else super.clusterMemberAddresses()
    }
    // The interrupt is reported as a provisioning failure (with the
    // InterruptedException as its cause); what matters is that removal happened
    // rather than being skipped as a "fatal" exception.
    val ex = intercept[InstanceProvisioningException](provider(cli).acquire(request()))
    assert(ex.getCause.isInstanceOf[InterruptedException])
    assert(cli.removed.contains("container-abc123"))
  }

  // ---------------------------------------------------------------------------
  // Release
  // ---------------------------------------------------------------------------

  "release" should "remove the container by id" in {
    val cli = new FakeDockerCli()
    val rented = provider(cli).acquire(request())
    provider(cli).release(rented)
    // acquire uses one provider instance and release another here only because
    // the fake is shared; assert against the shared cli.
    assert(cli.removed.contains(rented.instanceId))
  }

  it should "tolerate releasing an instance whose container is already gone" in {
    val cli = new FakeDockerCli() {
      override def remove(containerId: String): Unit =
        throw new RuntimeException("No such container")
    }
    val rented =
      RentedInstance("container-xyz", fourGiB, Some("pekko://Amber@172.17.0.5:2552"), "docker")
    // Release runs on failure paths where the container may already be dead;
    // that must not turn into a second failure.
    provider(cli).release(rented)
  }

  // ---------------------------------------------------------------------------
  // Full acquire -> release round trip
  // ---------------------------------------------------------------------------

  "a full round trip" should "acquire on the joined node, then remove on release" in {
    val cli = new FakeDockerCli()
    val p = provider(cli)
    val rented = p.acquire(request())
    assert(rented.nodeAddress.contains("pekko://Amber@172.17.0.5:2552"))
    p.release(rented)
    assert(cli.removed.contains("container-abc123"))
  }

  // ---------------------------------------------------------------------------
  // Per-operator image
  // ---------------------------------------------------------------------------

  "the docker run command" should "run the per-operator image when the request declares one" in {
    val cli = new FakeDockerCli()
    val perOperatorImage = "ghcr.io/sshiv012/texera-tiny-workflow-qc:1.0.0"
    provider(cli).acquire(
      InstanceRequest(
        instanceType = fourGiB,
        operatorId = "Op-1",
        executionId = 7L,
        image = Some(perOperatorImage)
      )
    )
    val cmd = cli.runCommands.head
    // The image is positional: it must sit immediately before the launcher
    // script, or docker would read it as another flag's value.
    val scriptIdx = cmd.indexOf(OffloadWorkerLauncher.WorkerLauncherScript)
    assert(scriptIdx > 0, "launcher script must be present")
    assert(cmd(scriptIdx - 1) == perOperatorImage)
    assert(
      !cmd.contains("texera-computing-unit-worker:latest"),
      "the configured default must not also appear"
    )
  }

  it should "fall back to the configured image when the request declares none" in {
    val cli = new FakeDockerCli()
    provider(cli).acquire(request())
    val cmd = cli.runCommands.head
    val scriptIdx = cmd.indexOf(OffloadWorkerLauncher.WorkerLauncherScript)
    assert(cmd(scriptIdx - 1) == "texera-computing-unit-worker:latest")
  }
}
