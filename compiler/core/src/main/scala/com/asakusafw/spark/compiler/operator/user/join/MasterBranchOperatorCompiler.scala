package com.asakusafw.spark.compiler
package operator
package user
package join

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.UserOperator
import com.asakusafw.spark.compiler.spi.OperatorType
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._
import com.asakusafw.vocabulary.operator.MasterBranch

class MasterBranchOperatorCompiler extends UserOperatorCompiler {

  override def support(operator: UserOperator)(implicit context: Context): Boolean = {
    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._
    annotationDesc.resolveClass == classOf[MasterBranch]
  }

  override def operatorType: OperatorType = OperatorType.CoGroupType

  override def compile(operator: UserOperator)(implicit context: Context): Type = {
    assert(support(operator))

    val operatorInfo = new OperatorInfo(operator)(context.jpContext)
    import operatorInfo._

    assert(inputs.size >= 2)
    assert(outputs.size > 0)

    outputs.foreach { output =>
      assert(output.dataModelType == inputs(MasterBranch.ID_INPUT_TRANSACTION).dataModelType)
    }

    methodDesc.parameterClasses
      .zip(inputs(MasterBranch.ID_INPUT_MASTER).dataModelClass
        +: inputs(MasterBranch.ID_INPUT_TRANSACTION).dataModelClass
        +: arguments.map(_.resolveClass))
      .foreach {
        case (method, model) => assert(method.isAssignableFrom(model))
      }

    val builder = new JoinOperatorFragmentClassBuilder(
      context.flowId,
      implementationClassType,
      outputs,
      inputs(MasterBranch.ID_INPUT_MASTER).dataModelType,
      inputs(MasterBranch.ID_INPUT_TRANSACTION).dataModelType,
      selectionMethod) {

      override def join(mb: MethodBuilder, ctrl: LoopControl, masterVar: Var, txVar: Var): Unit = {
        import mb._
        val branch = getOperatorField(mb)
          .invokeV(
            methodDesc.name,
            methodDesc.asType.getReturnType,
            masterVar.push().asType(methodDesc.asType.getArgumentTypes()(0))
              +: txVar.push().asType(methodDesc.asType.getArgumentTypes()(1))
              +: arguments.map { argument =>
                ldc(argument.value)(ClassTag(argument.resolveClass))
              }: _*)
        branch.dup().unlessNotNull {
          branch.pop()
          `throw`(pushNew0(classOf[NullPointerException].asType))
        }
        branchOutputMap.foreach {
          case (output, enum) =>
            branch.dup().unlessNe(
              getStatic(methodDesc.asType.getReturnType, enum.name, methodDesc.asType.getReturnType)) {
                getOutputField(mb, output)
                  .invokeV("add", txVar.push().asType(classOf[AnyRef].asType))
                branch.pop()
                ctrl.continue()
              }
        }
        branch.pop()
        `throw`(pushNew0(classOf[AssertionError].asType))
      }
    }

    context.jpContext.addClass(builder)
  }
}