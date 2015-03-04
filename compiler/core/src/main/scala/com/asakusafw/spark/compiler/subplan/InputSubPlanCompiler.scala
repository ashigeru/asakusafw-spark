package com.asakusafw.spark.compiler
package subplan

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.reflect.NameTransformer

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.{ ExternalInput, MarkerOperator }
import com.asakusafw.lang.compiler.planning.SubPlan
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.spark.compiler.operator._
import com.asakusafw.spark.compiler.spi.SubPlanCompiler
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.tools.asm._

class InputSubPlanCompiler extends SubPlanCompiler {

  def of: SubPlanType = SubPlanType.InputSubPlan

  def compile(subplan: SubPlan)(implicit context: Context): (Type, Array[Byte]) = {
    val inputs = subplan.getInputs.toSet[SubPlan.Input].map(_.getOperator)
    val heads = inputs.flatMap(_.getOutput.getOpposites.map(_.getOwner))
    assert(heads.size == 1)
    assert(heads.head.isInstanceOf[ExternalInput])
    val input = heads.head.asInstanceOf[ExternalInput]
    val inputRef = context.jpContext.addExternalInput(input.getName, input.getInfo)

    val outputs = subplan.getOutputs.toSet[SubPlan.Output].map(_.getOperator).toSeq

    implicit val compilerContext = OperatorCompiler.Context(context.jpContext)
    val operators = subplan.getOperators.filterNot(_ == input).map { operator =>
      operator -> OperatorCompiler.compile(operator)
    }.toMap
    context.fragments ++= operators.values

    val edges = subplan.getOperators.flatMap {
      _.getOutputs.collect {
        case output if output.getOpposites.size > 1 => output.getDataType.asType
      }
    }.map { dataType =>
      val builder = new EdgeFragmentClassBuilder(dataType)
      dataType -> (builder.thisType, builder.build())
    }.toMap
    context.fragments ++= edges.values

    val builder = new InputDriverClassBuilder(input.getDataType.asType, Type.LONG_TYPE) {

      override def outputMarkers: Seq[MarkerOperator] = outputs

      override def defMethods(methodDef: MethodDef): Unit = {
        super.defMethods(methodDef)

        methodDef.newMethod("paths", classOf[Set[String]].asType, Seq.empty) { mb =>
          import mb._
          val builder = getStatic(Set.getClass.asType, "MODULE$", Set.getClass.asType)
            .invokeV("newBuilder", classOf[mutable.Builder[_, _]].asType)
          inputRef.getPaths.toSeq.sorted.foreach { path =>
            builder.invokeI(NameTransformer.encode("+="),
              classOf[mutable.Builder[_, _]].asType, ldc(path).asType(classOf[AnyRef].asType))
          }
          `return`(builder.invokeI("result", classOf[AnyRef].asType).cast(classOf[Set[_]].asType))
        }

        methodDef.newMethod("fragments", classOf[(_, _)].asType, Seq.empty) { mb =>
          import mb._
          val nextLocal = new AtomicInteger(thisVar.nextLocal)

          val fragmentBuilder = new FragmentTreeBuilder(
            mb,
            operators.map {
              case (operator, (t, _)) => operator -> t
            },
            edges.map {
              case (dataType, (t, _)) => dataType -> t
            },
            nextLocal)
          val fragmentVar = fragmentBuilder.build(input.getOperatorPort)

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
    (builder.thisType, builder.build())
  }
}