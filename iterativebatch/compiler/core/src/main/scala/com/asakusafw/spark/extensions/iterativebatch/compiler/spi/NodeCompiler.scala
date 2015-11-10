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
package com.asakusafw.spark.extensions.iterativebatch.compiler.spi

import java.util.ServiceLoader

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.reference.ExternalInputReference
import com.asakusafw.lang.compiler.hadoop.InputFormatInfo
import com.asakusafw.lang.compiler.model.info.{ ExternalInputInfo, ExternalOutputInfo }
import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.spark.compiler.{
  ClassLoaderProvider,
  CompilerContext,
  DataModelLoaderProvider
}
import com.asakusafw.spark.compiler.planning.SubPlanInfo
import com.asakusafw.spark.compiler.spi.{ AggregationCompiler, OperatorCompiler }
import com.asakusafw.spark.compiler.subplan.{ Branching, BranchKeys }

import com.asakusafw.spark.extensions.iterativebatch.compiler.flow.Instantiator

trait NodeCompiler {

  def of: SubPlanInfo.DriverType

  def instantiator: Instantiator

  def compile(
    subplan: SubPlan)(
      implicit context: NodeCompiler.Context): Type
}

object NodeCompiler {

  trait Context
    extends CompilerContext
    with ClassLoaderProvider
    with DataModelLoaderProvider
    with Branching.Context {

    def branchKeys: BranchKeys

    def options: CompilerOptions

    def getInputFormatInfo(name: String, info: ExternalInputInfo): Option[InputFormatInfo]
    def addExternalInput(name: String, info: ExternalInputInfo): ExternalInputReference
    def addExternalOutput(name: String, info: ExternalOutputInfo, paths: Seq[String]): Unit

    def operatorCompilerContext: OperatorCompiler.Context
    def aggregationCompilerContext: AggregationCompiler.Context
  }

  def apply(
    driverType: SubPlanInfo.DriverType)(
      implicit context: NodeCompiler.Context): NodeCompiler = {
    apply(context.classLoader)(driverType)
  }

  def get(
    driverType: SubPlanInfo.DriverType)(
      implicit context: NodeCompiler.Context): Option[NodeCompiler] = {
    apply(context.classLoader).get(driverType)
  }

  def support(
    driverType: SubPlanInfo.DriverType)(
      implicit context: NodeCompiler.Context): Boolean = {
    get(driverType).isDefined
  }

  private[this] val nodeCompilers: mutable.Map[ClassLoader, Map[SubPlanInfo.DriverType, NodeCompiler]] = // scalastyle:ignore
    mutable.WeakHashMap.empty

  private[this] def apply(
    classLoader: ClassLoader): Map[SubPlanInfo.DriverType, NodeCompiler] = {
    nodeCompilers.getOrElse(classLoader, reload(classLoader))
  }

  private[this] def reload(
    classLoader: ClassLoader): Map[SubPlanInfo.DriverType, NodeCompiler] = {
    val ors = ServiceLoader.load(classOf[NodeCompiler], classLoader).map {
      resolver => resolver.of -> resolver
    }.toMap[SubPlanInfo.DriverType, NodeCompiler]
    nodeCompilers(classLoader) = ors
    ors
  }
}
