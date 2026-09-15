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

package org.apache.texera.common.compiler

import org.apache.texera.amber.core.offload.{OffloadConfig, SizingMode}
import org.apache.texera.amber.core.virtualidentity.WorkflowIdentity
import org.apache.texera.amber.core.workflow.{
  PreferPinnedAddress,
  RoundRobinPreference,
  WorkflowContext
}
import org.apache.texera.amber.core.workflow.PortIdentity
import org.apache.texera.amber.operator.filter.SpecializedFilterOpDesc
import org.apache.texera.amber.operator.source.scan.csv.CSVScanSourceOpDesc
import org.apache.texera.common.compiler.model.{LogicalLink, LogicalPlanPojo}
import org.apache.texera.common.config.OffloadConfigSettings
import org.apache.texera.common.offload.{InstanceCatalog, InstanceType}
import org.scalatest.flatspec.AnyFlatSpec

/**
  * Tests how an operator's offload declaration becomes a placement on its
  * physical operators.
  *
  * This is the step that connects the user-facing toggle to the engine's
  * pinned-address placement.
  */
class OffloadCompilationSpec extends AnyFlatSpec {

  private val catalog = InstanceCatalog(
    Seq(
      InstanceType("t3.medium", 2, 4.0, 0.0416),
      InstanceType("m5.large", 2, 8.0, 0.096)
    )
  )

  private def filterOp(cfg: OffloadConfig): SpecializedFilterOpDesc = {
    val op = new SpecializedFilterOpDesc
    op.predicates = List.empty
    op.offload = cfg
    op
  }

  /** Maps operator ids to the address their rented instance joined on. */
  private def addresses(pairs: (String, String)*): Map[String, String] = pairs.toMap

  // Most cases here exercise offload behaviour, so they need the gate open. The
  // gate itself has no default on the constructor -- an ungated planner would
  // silently validate declarations the runtime would never act on.
  private def enabledPlanner: OffloadPlanner =
    new OffloadPlanner(catalog, defaultSafetyFactor = 1.25, offloadEnabled = true)

  // The compiler resolves against the real offload.conf catalog, not the small
  // fixture above, so end-to-end tests must name an instance that really exists.
  private val realCatalogInstanceName =
    OffloadConfigSettings.catalog.instances.head.name

  private val realCsvPath =
    "workflow-compiling-service/src/test/resources/country_sales_small.csv"

  private def csvOp(): CSVScanSourceOpDesc = {
    val op = new CSVScanSourceOpDesc()
    op.fileName = Some(realCsvPath)
    op.customDelimiter = Some(",")
    op.hasHeader = true
    op
  }

  /**
    * Compiles a source -> filter chain and returns the result.
    *
    * The filter needs an upstream source: schema propagation fails on a bare
    * operator, which would mask the offload error under test.
    */
  private def compileWithSource(filter: SpecializedFilterOpDesc): WorkflowCompilationResult = {
    val csv = csvOp()
    new WorkflowCompiler(new WorkflowContext(workflowId = WorkflowIdentity(0))).compile(
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
      )
    )
  }

  // ---------------------------------------------------------------------------
  // Selecting which operators are offloaded
  // ---------------------------------------------------------------------------

  "OffloadPlanner" should "not treat a non-offloaded operator as offloaded" in {
    val op = filterOp(OffloadConfig())
    val planner = enabledPlanner
    assert(planner.offloadedOperators(List(op)).isEmpty)
  }

  it should "not treat an operator with a null offload block as offloaded" in {
    // A workflow saved before this feature has no offload block at all.
    val op = filterOp(OffloadConfig())
    op.offload = null
    val planner = enabledPlanner
    assert(planner.offloadedOperators(List(op)).isEmpty)
  }

  // ---------------------------------------------------------------------------
  // Offloaded -- error paths (negative)
  // ---------------------------------------------------------------------------

  it should "reject an offloaded operator naming an unknown instance type" in {
    val op = filterOp(OffloadConfig(enabled = true, instanceType = Some("m5.24xlarge")))
    val planner = enabledPlanner
    val ex = intercept[IllegalArgumentException](planner.validate(op))
    assert(ex.getMessage.contains("m5.24xlarge"))
  }

  it should "reject an offloaded operator in Manual mode with no instance type" in {
    val op = filterOp(OffloadConfig(enabled = true, sizingMode = SizingMode.MANUAL))
    val planner = enabledPlanner
    assertThrows[IllegalArgumentException](planner.validate(op))
  }

  it should "not validate an offload declaration when offloading is globally disabled" in {
    // With the master switch off nothing is ever rented, so a half-filled
    // declaration must not be what blocks a run that would otherwise execute.
    val op = filterOp(OffloadConfig(enabled = true, sizingMode = SizingMode.MANUAL))
    val disabled = new OffloadPlanner(catalog, defaultSafetyFactor = 1.25, offloadEnabled = false)
    disabled.validateIfOffloaded(op) // must not throw
  }

  it should "validate an offload declaration when offloading is enabled" in {
    val op = filterOp(OffloadConfig(enabled = true, sizingMode = SizingMode.MANUAL))
    val enabled = new OffloadPlanner(catalog, defaultSafetyFactor = 1.25, offloadEnabled = true)
    assertThrows[IllegalArgumentException](enabled.validateIfOffloaded(op))
  }

  it should "accept a valid manual declaration during validation" in {
    val op = filterOp(OffloadConfig(enabled = true, instanceType = Some("t3.medium")))
    val planner = enabledPlanner
    assert(planner.validate(op).name == "t3.medium")
  }

  // The image is positional in `docker run`; a flag-shaped one would be read as an
  // option and the launcher script after it taken for the image. Caught at compile
  // time, before the rental, so the user is told rather than shown a docker error.
  it should "reject an offloaded operator whose image would parse as a docker flag" in {
    val op = filterOp(
      OffloadConfig(
        enabled = true,
        instanceType = Some("t3.medium"),
        image = Some("--privileged")
      )
    )
    val ex = intercept[IllegalArgumentException](enabledPlanner.validate(op))
    assert(ex.getMessage.contains("cannot start with '-'"))
  }

  // A cleared text box arrives as "", which means the platform default. Compiling
  // it as an error would make an empty field unrecoverable in the editor.
  it should "accept an offloaded operator whose image box was cleared" in {
    val op =
      filterOp(OffloadConfig(enabled = true, instanceType = Some("t3.medium"), image = Some("")))
    assert(enabledPlanner.validate(op).name == "t3.medium")
  }

  it should "accept an offloaded operator declaring a real image" in {
    val op = filterOp(
      OffloadConfig(
        enabled = true,
        instanceType = Some("t3.medium"),
        image = Some("ghcr.io/acme/qc:1.0.0")
      )
    )
    assert(enabledPlanner.validate(op).name == "t3.medium")
  }

  // ---------------------------------------------------------------------------
  // Collecting the operators that need instances
  // ---------------------------------------------------------------------------

  "offloadedOperators" should "select only the operators marked for offloading" in {
    val plain = filterOp(OffloadConfig())
    val offloaded = filterOp(OffloadConfig(enabled = true, instanceType = Some("t3.medium")))
    val planner = enabledPlanner
    val selected = planner.offloadedOperators(List(plain, offloaded))
    assert(selected.map(_.operatorIdentifier.id) == List(offloaded.operatorIdentifier.id))
  }

  it should "return nothing when no operator is offloaded" in {
    val planner = enabledPlanner
    assert(planner.offloadedOperators(List(filterOp(OffloadConfig()))).isEmpty)
  }

  it should "tolerate an empty operator list" in {
    val planner = enabledPlanner
    assert(planner.offloadedOperators(List.empty).isEmpty)
  }

  // ---------------------------------------------------------------------------
  // End to end through the compiler: a bad declaration is an editor-visible
  // per-operator error, and a good one does not disturb compilation.
  // ---------------------------------------------------------------------------

  // These exercise the compiler against the real offload.conf, where offloading
  // is disabled by default. With the master switch off nothing is rented, so a
  // bad declaration must NOT block the run -- the validation gate and the
  // execution gate agree. Validation-when-enabled is covered by the
  // validateIfOffloaded tests above, which set the flag directly.
  private val offloadEnabledInConfig = OffloadConfigSettings.enabled

  "WorkflowCompiler" should "not block a run on a bad offload declaration when offloading is off" in {
    assume(!offloadEnabledInConfig, "offload.enabled is on; this asserts the disabled behaviour")
    val bad = filterOp(OffloadConfig(enabled = true, instanceType = Some("not-an-instance")))
    val result = compileWithSource(bad)
    assert(
      !result.operatorIdToError.contains(bad.operatorIdentifier),
      s"offloading is disabled, so nothing is rented and the run must proceed: " +
        s"${result.operatorIdToError}"
    )
  }

  it should "report an unknown offload instance type as an operator error when offloading is on" in {
    assume(offloadEnabledInConfig, "requires offload.enabled = true")
    val bad = filterOp(OffloadConfig(enabled = true, instanceType = Some("not-an-instance")))
    val result = compileWithSource(bad)
    val err = result.operatorIdToError.get(bad.operatorIdentifier)
    assert(err.isDefined, "an unknown instance type should surface as a compilation error")
    assert(err.get.message.contains("not-an-instance"))
  }

  it should "report Manual mode with no instance type as an operator error when offloading is on" in {
    assume(offloadEnabledInConfig, "requires offload.enabled = true")
    val bad = filterOp(OffloadConfig(enabled = true, sizingMode = SizingMode.MANUAL))
    val result = compileWithSource(bad)
    assert(result.operatorIdToError.contains(bad.operatorIdentifier))
  }

  it should "compile a valid offload declaration without introducing an error" in {
    val good = filterOp(
      OffloadConfig(enabled = true, instanceType = Some(realCatalogInstanceName))
    )
    val result = compileWithSource(good)
    assert(
      !result.operatorIdToError.contains(good.operatorIdentifier),
      s"unexpected error: ${result.operatorIdToError}"
    )
  }

  it should "compile an operator with no offload block unchanged" in {
    val plain = filterOp(OffloadConfig())
    val result = compileWithSource(plain)
    assert(
      !result.operatorIdToError.contains(plain.operatorIdentifier),
      s"unexpected error: ${result.operatorIdToError}"
    )
  }

  // ---------------------------------------------------------------------------
  // pinPlan: rewrite a whole compiled PhysicalPlan with rented addresses
  // ---------------------------------------------------------------------------

  /** Compiles a source -> filter chain and returns the physical plan. */
  private def compiledPlan(filter: SpecializedFilterOpDesc) = {
    val result = compileWithSource(filter)
    assert(result.physicalPlan.isDefined, s"compilation failed: ${result.operatorIdToError}")
    result.physicalPlan.get
  }

  "pinPlan" should "pin every physical op of an offloaded logical op to its address" in {
    val offloaded =
      filterOp(OffloadConfig(enabled = true, instanceType = Some(realCatalogInstanceName)))
    val plan = compiledPlan(offloaded)
    val planner = enabledPlanner

    val addr = "pekko://Amber@10.0.9.9:2552"
    val pinned = planner.pinPlan(plan, addresses(offloaded.operatorIdentifier.id -> addr))

    val filterPhysicalOps =
      pinned.operators.filter(_.id.logicalOpId == offloaded.operatorIdentifier)
    assert(filterPhysicalOps.nonEmpty)
    assert(
      filterPhysicalOps.forall(_.locationPreference.contains(PreferPinnedAddress(addr))),
      "every physical op of the offloaded logical op must be pinned"
    )
  }

  it should "leave the physical ops of non-offloaded operators untouched" in {
    val offloaded =
      filterOp(OffloadConfig(enabled = true, instanceType = Some(realCatalogInstanceName)))
    val plan = compiledPlan(offloaded)
    val planner = enabledPlanner

    val addr = "pekko://Amber@10.0.9.9:2552"
    val pinned = planner.pinPlan(plan, addresses(offloaded.operatorIdentifier.id -> addr))

    // The CSV source was not offloaded, so it must not have been pinned.
    val sourceOps =
      pinned.operators.filterNot(_.id.logicalOpId == offloaded.operatorIdentifier)
    assert(sourceOps.nonEmpty)
    assert(sourceOps.forall(op => !op.locationPreference.contains(PreferPinnedAddress(addr))))
  }

  it should "preserve a non-offloaded operator's existing location preference" in {
    // Pinning must not clobber a placement the compiler already chose (e.g. a
    // source operator that must run on the coordinator).
    val offloaded =
      filterOp(OffloadConfig(enabled = true, instanceType = Some(realCatalogInstanceName)))
    val plan = compiledPlan(offloaded)
    val planner = enabledPlanner

    val sourceOp = plan.operators.find(_.id.logicalOpId != offloaded.operatorIdentifier).get
    val planWithPreference =
      plan.setOperator(sourceOp.withLocationPreference(Some(RoundRobinPreference)))

    val pinned = planner.pinPlan(
      planWithPreference,
      addresses(offloaded.operatorIdentifier.id -> "pekko://Amber@10.0.9.9:2552")
    )
    assert(pinned.getOperator(sourceOp.id).locationPreference.contains(RoundRobinPreference))
  }

  it should "return the plan unchanged when there are no rented addresses" in {
    val plain = filterOp(OffloadConfig())
    val plan = compiledPlan(plain)
    val planner = enabledPlanner
    assert(planner.pinPlan(plan, Map.empty).operators == plan.operators)
  }

  it should "fail if a rented address names an operator absent from the plan" in {
    // A mismatch means the rental/plan bookkeeping diverged; pinning nothing
    // would silently run the operator un-pinned, so fail instead.
    val plan = compiledPlan(filterOp(OffloadConfig()))
    val planner = enabledPlanner
    assertThrows[IllegalStateException] {
      planner.pinPlan(plan, addresses("Ghost-Operator" -> "pekko://Amber@10.0.9.9:2552"))
    }
  }
}
