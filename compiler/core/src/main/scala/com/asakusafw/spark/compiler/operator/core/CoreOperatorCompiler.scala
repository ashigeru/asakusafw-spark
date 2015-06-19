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
package com.asakusafw.spark.compiler.operator.core

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.{ CoreOperator, Operator }
import com.asakusafw.spark.compiler.spi.OperatorCompiler

trait CoreOperatorCompiler extends OperatorCompiler {

  override def support(operator: Operator)(implicit context: Context): Boolean = {
    operator match {
      case op: CoreOperator => support(op)
      case _                => false
    }
  }

  def support(operator: CoreOperator)(implicit context: Context): Boolean

  override def compile(operator: Operator)(implicit context: Context): Type = {
    operator match {
      case op: CoreOperator => compile(op)
    }
  }

  def compile(operator: CoreOperator)(implicit context: Context): Type
}
