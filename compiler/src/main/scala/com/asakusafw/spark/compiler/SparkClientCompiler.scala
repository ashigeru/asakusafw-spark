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

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.slf4j.LoggerFactory

import com.asakusafw.lang.compiler.api.{ Exclusive, JobflowProcessor }
import com.asakusafw.lang.compiler.api.JobflowProcessor.{ Context => JPContext }
import com.asakusafw.lang.compiler.api.reference.{ CommandToken, ExternalInputReference }
import com.asakusafw.lang.compiler.common.Location
import com.asakusafw.lang.compiler.inspection.InspectionExtension
import com.asakusafw.lang.compiler.model.graph.Jobflow
import com.asakusafw.lang.compiler.planning.Plan
import com.asakusafw.spark.compiler.planning.SparkPlanning
import com.asakusafw.spark.compiler.subplan.{
  BranchKeysClassBuilder,
  BroadcastIdsClassBuilder
}

@Exclusive
class SparkClientCompiler extends JobflowProcessor {

  private val Logger = LoggerFactory.getLogger(getClass)

  override def process(jpContext: JPContext, source: Jobflow): Unit = {

    if (Logger.isDebugEnabled) {
      Logger.debug("Start Asakusafw Spark compiler.")
    }

    val plan = preparePlan(jpContext, source)

    InspectionExtension.inspect(
      jpContext, Location.of("META-INF/asakusa-spark/plan.json", '/'), plan)

    if (!jpContext.getOptions.verifyPlan) {

      val flowId = source.getFlowId
      val builder = new SparkClientClassBuilder(plan)(
        SparkClientCompiler.Context(
          flowId = flowId,
          jpContext = jpContext,
          externalInputs = mutable.Map.empty,
          branchKeys = new BranchKeysClassBuilder(flowId),
          broadcastIds = new BroadcastIdsClassBuilder(flowId)))

      val client = jpContext.addClass(builder)

      jpContext.addTask(
        SparkClientCompiler.ModuleName,
        SparkClientCompiler.ProfileName,
        SparkClientCompiler.Command,
        Seq(
          CommandToken.BATCH_ID,
          CommandToken.FLOW_ID,
          CommandToken.EXECUTION_ID,
          CommandToken.BATCH_ARGUMENTS,
          CommandToken.of(client.getClassName)))
    }
  }

  def preparePlan(jpContext: JPContext, source: Jobflow): Plan = {
    SparkPlanning.plan(jpContext, source).getPlan
  }
}

object SparkClientCompiler {

  val ModuleName: String = "spark"

  val ProfileName: String = "spark"

  val Command: Location = Location.of("spark/bin/spark-execute.sh")

  object Options {
    val SparkPlanVerify = "spark.plan.verify"
    val SparkInputDirect = "spark.input.direct"
  }

  case class Context(
    flowId: String,
    jpContext: JPContext,
    externalInputs: mutable.Map[String, ExternalInputReference],
    branchKeys: BranchKeysClassBuilder,
    broadcastIds: BroadcastIdsClassBuilder)
}