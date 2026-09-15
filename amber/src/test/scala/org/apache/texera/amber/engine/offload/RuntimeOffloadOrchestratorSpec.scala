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

import org.apache.texera.amber.core.offload.OffloadConfig
import org.apache.texera.amber.core.virtualidentity.WorkflowIdentity
import org.apache.texera.amber.core.workflow.{PreferPinnedAddress, WorkflowContext}
import org.apache.texera.amber.operator.filter.SpecializedFilterOpDesc
import org.apache.texera.amber.operator.source.scan.csv.CSVScanSourceOpDesc
import org.apache.texera.common.compiler.model.{LogicalLink, LogicalPlanPojo}
import org.apache.texera.common.compiler.{
  CompilationErrorHandling,
  OffloadPlanner,
  WorkflowCompiler
}
import org.apache.texera.amber.core.workflow.PortIdentity
import org.apache.texera.common.config.OffloadConfigSettings
import org.apache.texera.common.offload.{
  InstanceProvider,
  InstanceProvisioningException,
  InstanceRequest,
  RentedInstance
}
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/**
  * Tests the runtime orchestrator against a real compiled physical plan and a
  * fake provider: offloaded operators get pinned, rentals are released on
  * teardown, and a failure never leaves instances running.
  */
class RuntimeOffloadOrchestratorSpec extends AnyFlatSpec {

  private val realInstance = OffloadConfigSettings.catalog.instances.head.name
  private val realCsvPath =
    "workflow-compiling-service/src/test/resources/country_sales_small.csv"

  private class FakeProvider(failOnOperator: Option[String] = None) extends InstanceProvider {
    val acquiredFor: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    // The whole request, so the image the orchestrator resolved can be asserted on.
    val requests: mutable.ListBuffer[InstanceRequest] = mutable.ListBuffer.empty
    val released: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    private var n = 0
    override def name: String = "fake"
    override def acquire(request: InstanceRequest): RentedInstance = {
      if (failOnOperator.contains(request.operatorId))
        throw new InstanceProvisioningException(s"boom ${request.operatorId}")
      n += 1
      acquiredFor += request.operatorId
      requests += request
      RentedInstance(s"inst-$n", request.instanceType, Some(s"pekko://Amber@10.0.0.$n:2552"), name)
    }
    override def release(instance: RentedInstance): Unit = released += instance.instanceId
  }

  private def csvOp(): CSVScanSourceOpDesc = {
    val op = new CSVScanSourceOpDesc()
    op.fileName = Some(realCsvPath)
    op.customDelimiter = Some(",")
    op.hasHeader = true
    op
  }

  private def filterOp(cfg: OffloadConfig): SpecializedFilterOpDesc = {
    val op = new SpecializedFilterOpDesc
    op.predicates = List.empty
    op.offload = cfg
    op
  }

  /** Compiles csv -> filter and returns (logicalOps, physicalPlan). */
  private def compile(filter: SpecializedFilterOpDesc) = {
    val csv = csvOp()
    val result = new WorkflowCompiler(new WorkflowContext(workflowId = WorkflowIdentity(0)))
      .compile(
        LogicalPlanPojo(
          operators = List(csv, filter),
          links = List(
            LogicalLink(
              csv.operatorIdentifier,
              PortIdentity(0),
              filter.operatorIdentifier,
              PortIdentity(0)
            )
          ),
          opsToViewResult = List.empty,
          opsToReuseResult = List.empty
        ),
        CompilationErrorHandling.Strict
      )
    (List(csv, filter), result.physicalPlan.get)
  }

  private def orchestrator(provider: InstanceProvider, execId: Long = 1L) =
    new RuntimeOffloadOrchestrator(
      provider,
      // Gate forced open: these cases test the rent-then-pin flow itself, which
      // the runtime only reaches when offloading is enabled.
      new OffloadPlanner(
        OffloadConfigSettings.catalog,
        OffloadConfigSettings.defaultSafetyFactor,
        offloadEnabled = true
      ),
      execId
    )

  // ---------------------------------------------------------------------------
  // prepare: pin offloaded operators (positive)
  // ---------------------------------------------------------------------------

  "prepare" should "rent an instance and pin the offloaded operator's physical ops" in {
    val filter = filterOp(OffloadConfig(enabled = true, instanceType = Some(realInstance)))
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()

    val pinned = orchestrator(provider).prepare(logicalOps, plan)

    assert(provider.acquiredFor.toList == List(filter.operatorIdentifier.id))
    val filterOps = pinned.operators.filter(_.id.logicalOpId == filter.operatorIdentifier)
    assert(filterOps.nonEmpty)
    assert(filterOps.forall(_.locationPreference.exists(_.isInstanceOf[PreferPinnedAddress])))
  }

  // ---------------------------------------------------------------------------
  // prepare: the per-operator image reaches the rental
  // ---------------------------------------------------------------------------

  it should "carry an operator's declared image into its rental request" in {
    val filter = filterOp(
      OffloadConfig(
        enabled = true,
        instanceType = Some(realInstance),
        image = Some("ghcr.io/acme/qc:1.0.0")
      )
    )
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()

    orchestrator(provider).prepare(logicalOps, plan)

    assert(provider.requests.map(_.image).toList == List(Some("ghcr.io/acme/qc:1.0.0")))
  }

  it should "send no image when the operator declares none, so the default applies" in {
    val filter = filterOp(OffloadConfig(enabled = true, instanceType = Some(realInstance)))
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()

    orchestrator(provider).prepare(logicalOps, plan)

    assert(provider.requests.map(_.image).toList == List(None))
  }

  // The orchestrator must read resolvedImage, not the raw field: a cleared text
  // box arrives as "", and InstanceRequest refuses a blank image -- so a
  // regression here would throw mid-rentAll, after the first instance is rented.
  it should "treat a cleared image box as no image rather than failing the rental" in {
    val filter =
      filterOp(OffloadConfig(enabled = true, instanceType = Some(realInstance), image = Some("")))
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()

    orchestrator(provider).prepare(logicalOps, plan)

    assert(provider.requests.map(_.image).toList == List(None))
  }

  it should "leave the plan unchanged and rent nothing when no operator is offloaded" in {
    val filter = filterOp(OffloadConfig())
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()

    val out = orchestrator(provider).prepare(logicalOps, plan)

    assert(provider.acquiredFor.isEmpty)
    assert(out.operators == plan.operators)
  }

  // ---------------------------------------------------------------------------
  // release
  // ---------------------------------------------------------------------------

  "release" should "release every instance prepare rented" in {
    val filter = filterOp(OffloadConfig(enabled = true, instanceType = Some(realInstance)))
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()
    val orch = orchestrator(provider)

    orch.prepare(logicalOps, plan)
    orch.release()

    assert(provider.released.toList == List("inst-1"))
  }

  it should "be idempotent, releasing only once" in {
    val filter = filterOp(OffloadConfig(enabled = true, instanceType = Some(realInstance)))
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()
    val orch = orchestrator(provider)

    orch.prepare(logicalOps, plan)
    orch.release()
    orch.release()

    assert(provider.released.toList == List("inst-1"))
  }

  it should "do nothing when prepare rented nothing" in {
    val filter = filterOp(OffloadConfig())
    val (logicalOps, plan) = compile(filter)
    val provider = new FakeProvider()
    val orch = orchestrator(provider)

    orch.prepare(logicalOps, plan)
    orch.release()

    assert(provider.released.isEmpty)
  }

  // ---------------------------------------------------------------------------
  // failure paths (negative)
  // ---------------------------------------------------------------------------

  "prepare" should "propagate and release when renting fails partway" in {
    // Two offloaded operators; the second rental fails. The first must be
    // released so it does not keep running.
    val f1 = filterOp(OffloadConfig(enabled = true, instanceType = Some(realInstance)))
    val f2 = filterOp(OffloadConfig(enabled = true, instanceType = Some(realInstance)))
    val csv = csvOp()
    val result = new WorkflowCompiler(new WorkflowContext(workflowId = WorkflowIdentity(0)))
      .compile(
        LogicalPlanPojo(
          operators = List(csv, f1, f2),
          links = List(
            LogicalLink(
              csv.operatorIdentifier,
              PortIdentity(0),
              f1.operatorIdentifier,
              PortIdentity(0)
            ),
            LogicalLink(
              f1.operatorIdentifier,
              PortIdentity(0),
              f2.operatorIdentifier,
              PortIdentity(0)
            )
          ),
          opsToViewResult = List.empty,
          opsToReuseResult = List.empty
        ),
        CompilationErrorHandling.Strict
      )
    val plan = result.physicalPlan.get
    val provider = new FakeProvider(failOnOperator = Some(f2.operatorIdentifier.id))

    assertThrows[InstanceProvisioningException] {
      orchestrator(provider).prepare(List(csv, f1, f2), plan)
    }
    // f1's instance was rented, so it must have been released on the failure.
    assert(provider.released.toList == List("inst-1"))
  }

  "isNeeded" should "be false when no operator opts in" in {
    // With offloading globally disabled or nobody opting in, the runtime path is
    // skipped entirely.
    assert(!RuntimeOffloadOrchestrator.isNeeded(List(filterOp(OffloadConfig()))))
  }
}
