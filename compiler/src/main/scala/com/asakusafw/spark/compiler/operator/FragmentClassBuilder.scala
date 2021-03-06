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
package operator

import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable

import org.objectweb.asm.{ Opcodes, Type }
import org.objectweb.asm.signature.SignatureVisitor

import com.asakusafw.spark.compiler.operator.FragmentClassBuilder._
import com.asakusafw.spark.compiler.spi.OperatorCompiler
import com.asakusafw.spark.runtime.fragment.Fragment
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._

abstract class FragmentClassBuilder(
  val dataModelType: Type)(
    signature: Option[ClassSignatureBuilder],
    superType: Type)(
      implicit context: OperatorCompiler.Context)
  extends ClassBuilder(
    Type.getType(
      s"L${GeneratedClassPackageInternalName}/${context.flowId}/fragment/${nextName(superType)};"),
    signature,
    superType)

object FragmentClassBuilder {

  private[this] val curIds: mutable.Map[OperatorCompiler.Context, mutable.Map[String, AtomicLong]] = // scalastyle:ignore
    mutable.WeakHashMap.empty

  def nextName(superType: Type)(implicit context: OperatorCompiler.Context): String = {
    val superClassName = superType.getClassName
    val simpleName = superClassName.substring(superClassName.lastIndexOf('.') + 1)
    s"${simpleName}$$${
      curIds.getOrElseUpdate(context, mutable.Map.empty)
        .getOrElseUpdate(simpleName, new AtomicLong(0))
        .getAndIncrement()
    }"
  }
}
