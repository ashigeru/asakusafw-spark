/*
 * Copyright 2011-2015 Asakusa Framework Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asakusafw.spark.compiler
package subplan

import scala.collection.JavaConversions._

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.spark.compiler.planning.SubPlanInfo
import com.asakusafw.spark.compiler.spi.SubPlanCompiler

class ExtractSubPlanCompiler extends SubPlanCompiler {

  def of: SubPlanInfo.DriverType = SubPlanInfo.DriverType.EXTRACT

  override def instantiator: Instantiator = ExtractDriverInstantiator

  override def compile(
    subplan: SubPlan)(
      implicit context: SparkClientCompiler.Context): Type = {
    val subPlanInfo = subplan.getAttribute(classOf[SubPlanInfo])

    val inputs = subplan.getInputs.toSet[SubPlan.Input]
    assert(inputs.size == 1)

    val marker = inputs.head.getOperator

    val builder =
      new ExtractDriverClassBuilder(
        marker)(
        subPlanInfo.getLabel,
        subplan.getOutputs.toSeq)

    context.jpContext.addClass(builder)
  }
}