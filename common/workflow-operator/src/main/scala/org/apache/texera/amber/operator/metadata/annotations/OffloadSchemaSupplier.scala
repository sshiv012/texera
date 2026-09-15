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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.texera.common.config.OffloadConfigSettings
import org.apache.texera.common.offload.InstanceType

/**
  * Shapes the offload block in the property panel.
  *
  * Applied to the generated `OffloadConfig` definition rather than to the
  * `offload` property. The property is emitted as a bare `$ref`, and draft-07
  * ignores every keyword sitting beside a `$ref` -- so annotating the property
  * would produce labels that render nowhere.
  *
  * Three things the generated schema cannot say on its own:
  *
  *  - The instance list is data. It comes from offload.conf, so a fixed enum here
  *    would let the panel drift from what the platform can actually rent.
  *  - An instance name alone ("local-4g") does not describe the choice. Each
  *    option carries the memory and price that drive it, so the trade-off is
  *    visible where the decision is made instead of buried in a config file.
  *  - `enabled` gates the rest: an operator that is not offloaded should show one
  *    checkbox, not a form. See [[hideUnlessEnabled]] for why that gating cannot
  *    be expressed with `toggleHidden` from inside a nested definition.
  */
object OffloadSchemaSupplier {

  /** Name of the generated definition this shapes. */
  private val DefinitionName = "OffloadConfig"

  /**
    * Hides `field` whenever the sibling `enabled` checkbox is not ticked.
    *
    * `toggleHidden`, the other hiding keyword in this codebase, cannot do this:
    * the panel reads it only from the schema's top-level `properties`, resolving
    * the named fields against the top-level `fieldGroup`
    * (operator-property-edit-frame.component.ts, setFormlyFormBinding). Everything
    * here lives inside the nested `OffloadConfig` definition, so a `toggleHidden`
    * on `enabled` is never looked at and every field renders unconditionally.
    *
    * The hide* keywords are read per field as the schema is mapped, and resolve
    * their target against `field.parent.model` -- the enclosing group's model,
    * which for these fields is the offload block. `hideOnNull` covers an operator
    * saved before this block existed, whose model has no `enabled` at all.
    */
  private def hideUnlessEnabled(field: ObjectNode): Unit = {
    field.put(HideAnnotation.hideTarget, "enabled")
    field.put(HideAnnotation.hideType, HideAnnotation.Type.equals)
    field.put(HideAnnotation.hideExpectedValue, "false")
    field.put(HideAnnotation.hideOnNull, true)
  }

  /**
    * Rewrites the `OffloadConfig` definition in place, if the schema has one.
    *
    * A no-op for the operators that do not reference it, so it is safe to call for
    * every operator.
    */
  def refine(schema: JsonNode): Unit = {
    val definition = schema.at(s"/definitions/$DefinitionName")
    if (!definition.isObject) return
    val node = definition.asInstanceOf[ObjectNode]
    val properties = node.path("properties")
    if (!properties.isObject) return
    val props = properties.asInstanceOf[ObjectNode]

    // One checkbox until offloading is on. The gating is declared on each gated
    // field rather than as a list on `enabled`; see hideUnlessEnabled for why the
    // list form cannot work from inside a nested definition.
    Option(props.get("enabled")).collect { case o: ObjectNode => o }.foreach { enabled =>
      enabled.put("description", "Rent a machine for this operator when the workflow runs")
    }
    Seq("sizingMode", "instanceType", "image")
      .flatMap(name => Option(props.get(name)).collect { case o: ObjectNode => o })
      .foreach(hideUnlessEnabled)

    // The image is free text, not an enum: unlike the instance catalog, the
    // platform has no list of images it may run -- any registry reference the
    // Docker daemon can pull is valid. What it cannot be is silently empty, so
    // the description states what blank means.
    Option(props.get("image")).collect { case o: ObjectNode => o }.foreach { image =>
      image.put("title", "Container image")
      image.put(
        "description",
        "Image this operator's machine runs; leave blank for the platform default. " +
          "A custom image must still launch the Texera worker."
      )
    }

    // Only the modes that can actually provision are offered. Advised sizing needs
    // an estimated peak memory, and nothing produces one yet -- listing it would
    // let a user pick a mode whose only outcome is a compilation error. The enum
    // narrows to what works; SizingMode still defines both, so restoring Advised
    // here is a one-line change when the memory advisor lands.
    Option(props.get("sizingMode")).collect { case o: ObjectNode => o }.foreach { sizingMode =>
      sizingMode.put("title", "How to size it")
      sizingMode.put("description", "You choose the machine size for this operator")
      val modes = sizingMode.putArray("enum")
      modes.add("Manual")
    }

    Option(props.get("instanceType")).collect { case o: ObjectNode => o }.foreach { instanceType =>
      instanceType.put("title", "Machine size")
      instanceType
        .put("description", "Memory is the limit that matters; too small and the run fails")
      val values = instanceType.putArray("enum")
      val labels = instanceType.putArray("enumNames")
      OffloadConfigSettings.catalog.instances
        // Cheapest first, then by memory: the list reads as a ladder upward from
        // the smallest option, which is the order the choice is actually made in.
        .sortBy(i => (i.pricePerHour, i.memoryGiB, i.name))
        .foreach { instance =>
          values.add(instance.name)
          labels.add(optionLabel(instance))
        }
    }

    // safetyFactor is removed from the panel, not from the model. It only has an
    // effect in Advised sizing, which needs an estimated peak that nothing
    // produces yet; in Manual sizing the machine is named outright, so the
    // multiplier cannot change the outcome. A control that cannot change anything
    // is worse than no control. Workflow JSON still carries the field and the
    // platform default lives in offload.conf, so the memory advisor can surface it
    // once the number means something.
    props.remove("safetyFactor")
    Option(node.get("required"))
      .collect {
        case a: com.fasterxml.jackson.databind.node.ArrayNode =>
          a
      }
      .foreach { required =>
        val kept = required.elements().asInstanceOf[java.util.Iterator[JsonNode]]
        val retained = new java.util.ArrayList[String]()
        while (kept.hasNext) {
          val v = kept.next().asText()
          if (v != "safetyFactor") retained.add(v)
        }
        required.removeAll()
        retained.forEach(v => required.add(v))
      }
  }

  /**
    * One catalog entry as the panel shows it: name, memory, cost.
    *
    * Memory is the binding constraint and price is what being wrong in the safe
    * direction costs, so both belong on the option itself. Free local stand-ins
    * read "free" rather than "$0.0000/hr", which would look like a real price.
    */
  private[metadata] def optionLabel(instance: InstanceType): String = {
    val memory =
      if (instance.memoryGiB == instance.memoryGiB.floor) s"${instance.memoryGiB.toInt} GiB"
      else f"${instance.memoryGiB}%.1f GiB"
    val cost =
      if (instance.pricePerHour == 0.0) "free"
      else {
        // Trim trailing zeros so $0.0416 stays exact while $0.7680 reads as
        // $0.768 -- but keep at least two decimals, so a round price shows as
        // "$1.00/hr" rather than "$1." or "$1".
        val trimmed = f"${instance.pricePerHour}%.4f".reverse
          .dropWhile(_ == '0')
          .reverse
        val padded =
          if (trimmed.endsWith(".")) trimmed + "00"
          else if (trimmed.split('.').lift(1).exists(_.length == 1)) trimmed + "0"
          else trimmed
        s"$$$padded/hr"
      }
    s"${instance.name} — $memory · $cost"
  }
}
