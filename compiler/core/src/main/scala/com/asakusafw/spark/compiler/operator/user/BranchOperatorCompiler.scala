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
package operator
package user

import scala.reflect.ClassTag

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.UserOperator
import com.asakusafw.spark.compiler.spi.OperatorType
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._
import com.asakusafw.vocabulary.operator.Branch

class BranchOperatorCompiler extends UserOperatorCompiler {

  override def support(operator: UserOperator)(implicit context: Context): Boolean = {
    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._
    annotationDesc.resolveClass == classOf[Branch]
  }

  override def operatorType: OperatorType = OperatorType.MapType

  override def compile(operator: UserOperator)(implicit context: Context): Type = {

    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._

    assert(support(operator),
      s"The operator type is not supported: ${annotationDesc.resolveClass.getSimpleName}")
    assert(inputs.size == 1, // FIXME to take multiple inputs for side data?
      s"The size of inputs should be 1: ${inputs.size}")
    assert(outputs.size > 0,
      s"The size of outputs should be greater than 0: ${outputs.size}")

    assert(
      outputs.forall { output =>
        output.dataModelType == inputs(Branch.ID_INPUT).dataModelType
      },
      s"All of output types should be the same: ${outputs.map(_.dataModelType).mkString("(", ",", ")")}")

    assert(
      methodDesc.parameterClasses
        .zip(inputs.map(_.dataModelClass)
          ++: arguments.map(_.resolveClass))
        .forall {
          case (method, model) => method.isAssignableFrom(model)
        },
      s"The operator method parameter types are not compatible: (${
        methodDesc.parameterClasses.map(_.getName).mkString("(", ",", ")")
      }, ${
        (inputs.map(_.dataModelClass)
          ++: arguments.map(_.resolveClass)).map(_.getName).mkString("(", ",", ")")
      })")

    val builder = new UserOperatorFragmentClassBuilder(
      context.flowId,
      inputs(Branch.ID_INPUT).dataModelType,
      implementationClassType,
      outputs) {

      override def defAddMethod(mb: MethodBuilder, dataModelVar: Var): Unit = {
        import mb._
        val branch = getOperatorField(mb)
          .invokeV(
            methodDesc.name,
            methodDesc.asType.getReturnType,
            dataModelVar.push().asType(methodDesc.asType.getArgumentTypes()(0))
              +: arguments.map { argument =>
                ldc(argument.value)(ClassTag(argument.resolveClass))
              }: _*)
        branch.dup().unlessNotNull {
          `throw`(pushNew0(classOf[NullPointerException].asType))
        }
        branchOutputMap.foreach {
          case (output, enum) =>
            branch.dup().unlessNe(
              getStatic(methodDesc.asType.getReturnType, enum.name, methodDesc.asType.getReturnType)) {
                getOutputField(mb, output)
                  .invokeV("add", dataModelVar.push().asType(classOf[AnyRef].asType))
                `return`()
              }
        }
        `throw`(pushNew0(classOf[AssertionError].asType))
      }
    }

    context.jpContext.addClass(builder)
  }
}
