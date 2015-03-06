package com.asakusafw.spark.compiler
package subplan

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.reflect.NameTransformer

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph._
import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.lang.compiler.planning.spark.DominantOperator
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.spark.compiler.operator._
import com.asakusafw.spark.compiler.spi.SubPlanCompiler
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.tools.asm._
import com.asakusafw.spark.tools.asm.MethodBuilder._
import com.asakusafw.vocabulary.operator.CoGroup

class CoGroupSubPlanCompiler extends SubPlanCompiler {

  override def of(operator: Operator, classLoader: ClassLoader): Boolean = {
    operator match {
      case op: UserOperator =>
        op.getAnnotation.resolve(classLoader).annotationType == classOf[CoGroup]
      case _ => false
    }
  }

  override def instantiator: Instantiator = ???

  override def compile(subplan: SubPlan)(implicit context: Context): Type = {
    val dominant = subplan.getAttribute(classOf[DominantOperator]).getDominantOperator
    assert(dominant.isInstanceOf[UserOperator])
    val operator = dominant.asInstanceOf[UserOperator]

    val outputs = subplan.getOutputs.toSet[SubPlan.Output].map(_.getOperator).toSeq

    implicit val compilerContext = OperatorCompiler.Context(context.flowId, context.jpContext)
    val operators = subplan.getOperators.map { operator =>
      operator.getOriginalSerialNumber -> OperatorCompiler.compile(operator)
    }.toMap[Long, Type]

    val edges = subplan.getOperators.flatMap {
      _.getOutputs.collect {
        case output if output.getOpposites.size > 1 => output.getDataType.asType
      }
    }.map { dataType =>
      dataType -> EdgeFragmentClassBuilder.getOrCompile(context.flowId, dataType, context.jpContext)
    }.toMap

    val builder = new CoGroupDriverClassBuilder(context.flowId, classOf[AnyRef].asType) {

      override def jpContext = context.jpContext

      override def outputMarkers: Seq[MarkerOperator] = outputs

      override def defMethods(methodDef: MethodDef): Unit = {
        super.defMethods(methodDef)

        methodDef.newMethod("fragments", classOf[(_, _)].asType, Seq.empty) { mb =>
          import mb._
          val nextLocal = new AtomicInteger(thisVar.nextLocal)

          val fragmentBuilder = new FragmentTreeBuilder(
            mb,
            operators,
            edges,
            nextLocal)
          val fragmentVar = fragmentBuilder.build(operator)

          val outputsVar = {
            val builder = getStatic(Map.getClass.asType, "MODULE$", Map.getClass.asType)
              .invokeV("newBuilder", classOf[mutable.Builder[_, _]].asType)
            outputs.sortBy(_.getOriginalSerialNumber).foreach { op =>
              builder.invokeI(NameTransformer.encode("+="),
                classOf[mutable.Builder[_, _]].asType,
                getStatic(Tuple2.getClass.asType, "MODULE$", Tuple2.getClass.asType).
                  invokeV("apply", classOf[(_, _)].asType,
                    ldc(op.getOriginalSerialNumber).box().asType(classOf[AnyRef].asType),
                    fragmentBuilder.vars(op.getOriginalSerialNumber).push().asType(classOf[AnyRef].asType))
                  .asType(classOf[AnyRef].asType))
            }
            val map = builder.invokeI("result", classOf[AnyRef].asType).cast(classOf[Map[_, _]].asType)
            map.store(nextLocal.getAndAdd(map.size))
          }

          `return`(
            getStatic(Tuple2.getClass.asType, "MODULE$", Tuple2.getClass.asType).
              invokeV("apply", classOf[(_, _)].asType,
                fragmentVar.push().asType(classOf[AnyRef].asType), outputsVar.push().asType(classOf[AnyRef].asType)))
        }
      }
    }

    context.jpContext.addClass(builder)
  }
}
