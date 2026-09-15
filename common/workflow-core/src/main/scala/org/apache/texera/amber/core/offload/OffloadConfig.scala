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

package org.apache.texera.amber.core.offload

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonIgnoreProperties}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/**
  * Per-operator declaration that this operator runs on its own rented instance.
  *
  * Attached to a logical operator and carried in the workflow document, so it
  * must tolerate absent fields: workflows saved before this feature existed have
  * no offload block, and must keep running unchanged.
  *
  * @param enabled      whether to offload this operator to a rented instance
  * @param instanceType provider instance type; required in [[SizingMode.MANUAL]]
  * @param sizingMode   how the instance type is chosen
  * @param safetyFactor per-operator override for the headroom multiplier applied
  *                     to an estimated peak before selecting an instance; must be
  *                     >= 1.0. None means "use offload.default-safety-factor" --
  *                     a hardcoded default here would shadow that config knob and
  *                     make it dead.
  *
  *                     Not shown in the property panel. It only has an effect in
  *                     ADVISED sizing, which needs an estimated peak that nothing
  *                     produces yet; in MANUAL sizing the instance is named
  *                     outright, so the multiplier is inert. A control that
  *                     cannot change the outcome is worse than no control. The
  *                     field and its plumbing stay so the memory advisor can
  *                     expose it once the number means something.
  * @param image        image the rented instance runs, letting one operator bring
  *                     its own tooling instead of the platform default. The image
  *                     must still launch the Texera worker, since the instance
  *                     becomes usable only by joining the cluster. None means
  *                     "use offload.docker-image", which is what every workflow
  *                     saved before this field existed carries.
  *
  *                     Read it through [[resolvedImage]] rather than directly:
  *                     the panel renders this as a free-text box, and a box the
  *                     user typed in and then cleared serializes as "", which
  *                     means the default just as much as an absent field does.
  */
@JsonIgnoreProperties(ignoreUnknown = true)
case class OffloadConfig(
    enabled: Boolean = false,
    instanceType: Option[String] = None,
    sizingMode: SizingMode = SizingMode.MANUAL,
    // Option[Double] erases to Option[Object], so without an explicit content type
    // the schema generator emits a $ref to the empty `Object` definition and the
    // property panel can never hold a number.
    @JsonDeserialize(contentAs = classOf[java.lang.Double])
    safetyFactor: Option[Double] = None,
    // Option[String] needs no explicit content type: String is not erased to
    // Object the way a boxed Double is, so the schema generator emits a real
    // string property.
    image: Option[String] = None
) {
  require(
    instanceType.forall(_.trim.nonEmpty),
    "instanceType must be non-blank when supplied"
  )
  require(safetyFactor.forall(_ >= 1.0), "safetyFactor must be at least 1.0 when supplied")

  @JsonIgnore
  def isOffloaded: Boolean = enabled

  /**
    * The declared image, or None to mean the platform default.
    *
    * Blank collapses to None rather than failing: `instanceType` can afford a
    * constructor `require` because its schema carries an `enum`, so the browser
    * rejects "" long before Jackson sees it. `image` is a free-text box with no
    * enum and no minLength, so a user who types in it and clears it saves
    * `"image": ""` -- and an empty box means the default, not a broken workflow.
    */
  @JsonIgnore
  def resolvedImage: Option[String] = image.map(_.trim).filter(_.nonEmpty)

  /**
    * Why this configuration cannot be provisioned, if it cannot.
    *
    * Kept separate from the constructor `require`s so the frontend can surface a
    * message on a half-filled form instead of failing deserialization.
    */
  @JsonIgnore
  def validationError: Option[String] = {
    if (!enabled) None
    else if (sizingMode == SizingMode.MANUAL && instanceType.isEmpty)
      Some("An instance type must be selected when sizing mode is Manual.")
    // The image is a positional argument to `docker run`, so one starting with
    // `-` is read as a flag and the launcher script after it is taken for the
    // image. Caught here, at compile time, so the user is told what is wrong
    // instead of watching the rental fail with a docker usage error.
    else if (resolvedImage.exists(_.startsWith("-")))
      Some("A container image cannot start with '-'.")
    else None
  }
}
