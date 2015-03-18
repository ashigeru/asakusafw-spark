package com.asakusafw.spark.compiler.operator
package core

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.nio.file.Files

import scala.collection.JavaConversions._

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.api.reference.DataModelReference
import com.asakusafw.lang.compiler.model.description.ClassDescription
import com.asakusafw.lang.compiler.model.graph.CoreOperator
import com.asakusafw.lang.compiler.model.graph.CoreOperator.CoreOperatorKind
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.spi.CoreOperatorCompiler
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.tools.asm._

@RunWith(classOf[JUnitRunner])
class ExtendOperatorCompilerSpecTest extends ExtendOperatorCompilerSpec

class ExtendOperatorCompilerSpec extends FlatSpec with LoadClassSugar {

  import ExtendOperatorCompilerSpec._

  behavior of classOf[ExtendOperatorCompiler].getSimpleName

  def resolvers = CoreOperatorCompiler(Thread.currentThread.getContextClassLoader)

  it should "compile Extend operator" in {
    val operator = CoreOperator.builder(CoreOperatorKind.EXTEND)
      .input("input", ClassDescription.of(classOf[InputModel]))
      .output("output", ClassDescription.of(classOf[OutputModel]))
      .build()

    val compiler = resolvers(CoreOperatorKind.EXTEND)
    val classpath = Files.createTempDirectory("ExtendOperatorCompilerSpec").toFile
    val context = OperatorCompiler.Context(
      flowId = "flowId",
      jpContext = new MockJobflowProcessorContext(
        new CompilerOptions("buildid", "", Map.empty[String, String]),
        Thread.currentThread.getContextClassLoader,
        classpath))
    val thisType = compiler.compile(operator)(context)
    val cls = loadClass(thisType.getClassName, classpath).asSubclass(classOf[Fragment[InputModel]])

    val out = {
      val builder = new OutputFragmentClassBuilder(context.flowId, classOf[OutputModel].asType)
      val cls = loadClass(builder.thisType.getClassName, builder.build())
        .asSubclass(classOf[OutputFragment[OutputModel]])
      cls.newInstance()
    }

    val fragment = cls.getConstructor(classOf[Fragment[_]]).newInstance(out)

    val dm = new InputModel()
    for (i <- 0 until 10) {
      dm.i.modify(i)
      fragment.add(dm)
    }
    assert(out.buffer.size === 10)
    out.buffer.zipWithIndex.foreach {
      case (dm, i) =>
        assert(dm.i.get === i)
        assert(dm.l.isNull)
    }
    fragment.reset()
    assert(out.buffer.size === 0)
  }
}

object ExtendOperatorCompilerSpec {

  class InputModel extends DataModel[InputModel] {

    val i: IntOption = new IntOption()

    override def reset: Unit = {
      i.setNull()
    }

    override def copyFrom(other: InputModel): Unit = {
      i.copyFrom(other.i)
    }

    def getIOption: IntOption = i
  }

  class OutputModel extends DataModel[OutputModel] {

    val i: IntOption = new IntOption()
    val l: LongOption = new LongOption()

    override def reset: Unit = {
      i.setNull()
      l.setNull()
    }

    override def copyFrom(other: OutputModel): Unit = {
      i.copyFrom(other.i)
      l.copyFrom(other.l)
    }

    def getIOption: IntOption = i
    def getLOption: LongOption = l
  }
}
