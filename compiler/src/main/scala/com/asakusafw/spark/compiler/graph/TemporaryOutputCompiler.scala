/*
 * Copyright 2011-2019 Asakusa Framework Team.
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
package graph

import com.asakusafw.bridge.hadoop.temporary.TemporaryFileOutputFormat

import scala.collection.JavaConversions._
import org.objectweb.asm.Type
import com.asakusafw.lang.compiler.extension.directio.DirectFileIoModels
import com.asakusafw.lang.compiler.model.graph.ExternalOutput
import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.spark.compiler.planning.SubPlanInfo
import com.asakusafw.spark.compiler.spi.NodeCompiler

class TemporaryOutputCompiler extends NodeCompiler {

  override def support(
    subplan: SubPlan)(
      implicit context: NodeCompiler.Context): Boolean = {
    val subPlanInfo = subplan.getAttribute(classOf[SubPlanInfo])
    val primaryOperator = subPlanInfo.getPrimaryOperator
    if (primaryOperator.isInstanceOf[ExternalOutput]) {
      val operator = primaryOperator.asInstanceOf[ExternalOutput]
      if (context.options.useOutputDirect) {
        Option(operator.getInfo).map { info =>
          !DirectFileIoModels.isSupported(info)
        }.getOrElse(true)
      } else {
        true
      }
    } else {
      false
    }
  }

  override def instantiator: Instantiator = TemporaryOutputInstantiator

  override def compile(
    subplan: SubPlan)(
      implicit context: NodeCompiler.Context): Type = {
    assert(support(subplan), s"The subplan is not supported: ${subplan}")

    val subPlanInfo = subplan.getAttribute(classOf[SubPlanInfo])
    val primaryOperator = subPlanInfo.getPrimaryOperator
    assert(primaryOperator.isInstanceOf[ExternalOutput],
      s"The primary operator should be external output: ${primaryOperator} [${subplan}]")
    val operator = primaryOperator.asInstanceOf[ExternalOutput]

    context.addExternalOutput(
      operator.getName, operator.getInfo,
      Seq(context.options.getRuntimeWorkingPath(
        s"${operator.getName}/*/${TemporaryFileOutputFormat.DEFAULT_FILE_NAME}-*")))

    val builder =
      new TemporaryOutputClassBuilder(
        operator)(
        subplan.label)

    context.addClass(builder)
  }
}
