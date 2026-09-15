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

package org.apache.texera.amber.operator.metadata.annotations

import org.apache.texera.amber.operator.metadata.OperatorMetadataGenerator
import org.apache.texera.common.config.OffloadConfigSettings
import org.apache.texera.common.offload.InstanceType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/**
  * Tests the offload block as the property panel receives it.
  *
  * These assert on the generated `OffloadConfig` definition, not on the `offload`
  * property: the property is a bare `$ref`, and draft-07 ignores keywords beside
  * a `$ref`, so anything written there renders nowhere. A regression that moved
  * the refinement back onto the property would leave these failing.
  */
class OffloadSchemaSupplierSpec extends AnyFlatSpec with Matchers {

  private def offloadDefinition =
    OperatorMetadataGenerator
      .generateOperatorJsonSchema(OperatorMetadataGenerator.operatorTypeMap.keys.head)
      .at("/definitions/OffloadConfig")

  private def property(name: String) = offloadDefinition.at(s"/properties/$name")

  "the offload block" should "exist in the generated schema" in {
    offloadDefinition.isMissingNode shouldBe false
  }

  // ---------------------------------------------------------------------------
  // What the panel must NOT show
  // ---------------------------------------------------------------------------

  it should "not offer a safety factor" in {
    // It only has an effect in Advised sizing, which cannot run yet, and in Manual
    // sizing the machine is named outright so the multiplier is inert. A control
    // that cannot change the outcome is worse than no control.
    property("safetyFactor").isMissingNode shouldBe true
  }

  it should "not list safetyFactor as required" in {
    val required =
      offloadDefinition.path("required").elements().asScala.map(_.asText()).toSeq
    required should not contain "safetyFactor"
  }

  it should "not offer Advised sizing while it cannot provision" in {
    // Offering it would let a user pick a mode whose only outcome is a
    // compilation error.
    val modes = property("sizingMode").path("enum").elements().asScala.map(_.asText()).toSeq
    modes shouldBe Seq("Manual")
  }

  // ---------------------------------------------------------------------------
  // Progressive disclosure: one checkbox until offloading is on
  // ---------------------------------------------------------------------------

  it should "gate the sizing fields behind the enable toggle" in {
    val toggled =
      property("enabled").path("toggleHidden").elements().asScala.map(_.asText()).toSeq
    toggled should contain allOf ("sizingMode", "instanceType")
  }

  it should "gate the image field behind the enable toggle too" in {
    val toggled =
      property("enabled").path("toggleHidden").elements().asScala.map(_.asText()).toSeq
    toggled should contain("image")
  }

  // ---------------------------------------------------------------------------
  // Per-operator image
  // ---------------------------------------------------------------------------

  it should "offer an image field the operator can override" in {
    property("image").isMissingNode shouldBe false
    property("image").path("type").asText() shouldBe "string"
  }

  it should "describe the image field so the default is discoverable" in {
    // Left blank the platform default is used, which is not guessable from an
    // empty text box.
    property("image").path("title").asText() should not be empty
    property("image").path("description").asText() should not be empty
  }

  it should "not require an image, so existing workflows stay valid" in {
    val required =
      offloadDefinition.path("required").elements().asScala.map(_.asText()).toSeq
    required should not contain "image"
  }

  // ---------------------------------------------------------------------------
  // The machine list is data, and says what is being chosen
  // ---------------------------------------------------------------------------

  it should "offer exactly the machines the platform can rent" in {
    // Hardcoding names here would let the panel drift from offload.conf.
    val offered = property("instanceType").path("enum").elements().asScala.map(_.asText()).toSeq
    offered.toSet shouldBe OffloadConfigSettings.catalog.instances.map(_.name).toSet
  }

  it should "label every machine with its memory and cost" in {
    val labels =
      property("instanceType").path("enumNames").elements().asScala.map(_.asText()).toSeq
    val values = property("instanceType").path("enum").elements().asScala.map(_.asText()).toSeq

    labels.size shouldBe values.size
    // Memory is the binding constraint on the choice, so it must be on the option
    // itself rather than left for the user to look up.
    all(labels) should include("GiB")
    labels.zip(values).foreach { case (label, value) => label should startWith(value) }
  }

  it should "order the machines cheapest first, then by memory" in {
    // The list reads as a ladder upward from the smallest option, which is the
    // order the choice is actually made in.
    val values = property("instanceType").path("enum").elements().asScala.map(_.asText()).toSeq
    val byName = OffloadConfigSettings.catalog.instances.map(i => i.name -> i).toMap
    val keys = values.map(byName).map(i => (i.pricePerHour, i.memoryGiB))
    keys shouldBe keys.sorted
  }

  // ---------------------------------------------------------------------------
  // Label formatting
  // ---------------------------------------------------------------------------

  "optionLabel" should "read free rather than a zero price for local stand-ins" in {
    // "$0.0000/hr" would look like a real price.
    OffloadSchemaSupplier.optionLabel(InstanceType("local-2g", 2, 2.0, 0.0)) shouldBe
      "local-2g — 2 GiB · free"
  }

  it should "show a whole-number memory without a decimal point" in {
    OffloadSchemaSupplier.optionLabel(InstanceType("m5.large", 2, 8.0, 0.096)) shouldBe
      "m5.large — 8 GiB · $0.096/hr"
  }

  it should "keep a fractional memory size" in {
    OffloadSchemaSupplier.optionLabel(InstanceType("odd", 2, 7.5, 0.5)) should
      include("7.5 GiB")
  }

  it should "trim trailing zeros from a price but keep it looking like money" in {
    // Exact prices stay exact...
    OffloadSchemaSupplier.optionLabel(InstanceType("a", 2, 4.0, 0.0416)) should
      include("$0.0416/hr")
    OffloadSchemaSupplier.optionLabel(InstanceType("b", 2, 4.0, 0.768)) should
      include("$0.768/hr")
    // ...and a round price still reads as currency, not "$1." or "$1".
    OffloadSchemaSupplier.optionLabel(InstanceType("c", 2, 4.0, 1.0)) should include("$1.00/hr")
    OffloadSchemaSupplier.optionLabel(InstanceType("d", 2, 4.0, 0.5)) should include("$0.50/hr")
  }
}
