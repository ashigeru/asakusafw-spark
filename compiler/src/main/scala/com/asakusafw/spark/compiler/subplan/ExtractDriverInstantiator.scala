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

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConversions._
import scala.concurrent.Future

import org.apache.spark.rdd.RDD
import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.spark.compiler.planning.SubPlanInputInfo
import com.asakusafw.spark.compiler.spi.SubPlanCompiler
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._

object ExtractDriverInstantiator
  extends Instantiator
  with ScalaIdioms {

  override def newInstance(
    driverType: Type,
    subplan: SubPlan)(
      mb: MethodBuilder,
      vars: Instantiator.Vars,
      nextLocal: AtomicInteger)(
        implicit context: SparkClientCompiler.Context): Var = {
    import mb._ // scalastyle:ignore

    val extractDriver = pushNew(driverType)
    extractDriver.dup().invokeInit(
      vars.sc.push(),
      vars.hadoopConf.push(),
      vars.broadcasts.push(),
      buildSeq(mb) { builder =>
        for {
          subPlanInput <- subplan.getInputs.toSet[SubPlan.Input]
          inputInfo <- Option(subPlanInput.getAttribute(classOf[SubPlanInputInfo]))
          if inputInfo.getInputType == SubPlanInputInfo.InputType.DONT_CARE
          prevSubPlanOutput <- subPlanInput.getOpposites.toSeq
          marker = prevSubPlanOutput.getOperator
        } {
          builder +=
            applyMap(mb)(
              vars.rdds.push(),
              context.branchKeys.getField(mb, marker))
            .cast(classOf[Future[RDD[(_, _)]]].asType)
        }
      })
    extractDriver.store(nextLocal.getAndAdd(extractDriver.size))
  }
}