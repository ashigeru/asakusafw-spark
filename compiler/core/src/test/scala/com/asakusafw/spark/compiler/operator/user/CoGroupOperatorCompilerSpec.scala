package com.asakusafw.spark.compiler.operator
package user

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.nio.file.Files
import java.util.{ List => JList }

import scala.collection.JavaConversions._

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.mock.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.model.PropertyName
import com.asakusafw.lang.compiler.model.description._
import com.asakusafw.lang.compiler.model.graph.Group
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.spi.UserOperatorCompiler
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.operator.CoGroup

@RunWith(classOf[JUnitRunner])
class CoGroupOperatorCompilerSpecTest extends CoGroupOperatorCompilerSpec

class CoGroupOperatorCompilerSpec extends FlatSpec with LoadClassSugar {

  import CoGroupOperatorCompilerSpec._

  behavior of classOf[CoGroupOperatorCompiler].getSimpleName

  def resolvers = UserOperatorCompiler(Thread.currentThread.getContextClassLoader)

  it should "compile CoGroup operator" in {
    val operator = OperatorExtractor
      .extract(classOf[CoGroup], classOf[CoGroupOperator], "cogroup")
      .input("hogeList", ClassDescription.of(classOf[Hoge]),
        new Group(Seq(PropertyName.of("id")), Seq.empty[Group.Ordering]))
      .input("fooList", ClassDescription.of(classOf[Foo]),
        new Group(
          Seq(PropertyName.of("hogeId")),
          Seq(new Group.Ordering(PropertyName.of("id"), Group.Direction.ASCENDANT))))
      .output("hogeResult", ClassDescription.of(classOf[Hoge]))
      .output("fooResult", ClassDescription.of(classOf[Foo]))
      .output("hogeError", ClassDescription.of(classOf[Hoge]))
      .output("fooError", ClassDescription.of(classOf[Foo]))
      .output("nResult", ClassDescription.of(classOf[N]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    val compiler = resolvers(classOf[CoGroup])
    val classpath = Files.createTempDirectory("CoGroupOperatorCompilerSpec").toFile
    val context = OperatorCompiler.Context(
      flowId = "flowId",
      jpContext = new MockJobflowProcessorContext(
        new CompilerOptions("buildid", "", Map.empty[String, String]),
        Thread.currentThread.getContextClassLoader,
        classpath))
    val thisType = compiler.compile(operator)(context)
    val cls = loadClass(thisType.getClassName, classpath).asSubclass(classOf[CoGroupFragment])

    val (hogeResult, hogeError) = {
      val builder = new OutputFragmentClassBuilder(context.flowId, classOf[Hoge].asType)
      val cls = loadClass(builder.thisType.getClassName, builder.build()).asSubclass(classOf[OutputFragment[Hoge]])
      (cls.newInstance, cls.newInstance)
    }

    val (fooResult, fooError) = {
      val builder = new OutputFragmentClassBuilder(context.flowId, classOf[Foo].asType)
      val cls = loadClass(builder.thisType.getClassName, builder.build()).asSubclass(classOf[OutputFragment[Foo]])
      (cls.newInstance, cls.newInstance)
    }

    val nResult = {
      val builder = new OutputFragmentClassBuilder(context.flowId, classOf[N].asType)
      val cls = loadClass(builder.thisType.getClassName, builder.build()).asSubclass(classOf[OutputFragment[N]])
      cls.newInstance
    }

    val fragment = cls.getConstructor(
      classOf[Fragment[_]], classOf[Fragment[_]],
      classOf[Fragment[_]], classOf[Fragment[_]],
      classOf[Fragment[_]])
      .newInstance(hogeResult, fooResult, hogeError, fooError, nResult)

    {
      val hoges = Seq.empty[Hoge]
      val foos = Seq.empty[Foo]
      fragment.add(Seq(hoges, foos))
      assert(hogeResult.buffer.size === 0)
      assert(fooResult.buffer.size === 0)
      assert(hogeError.buffer.size === 0)
      assert(fooError.buffer.size === 0)
      assert(nResult.buffer.size === 1)
      assert(nResult.buffer(0).n.get === 10)
    }

    fragment.reset()
    assert(hogeResult.buffer.size === 0)
    assert(fooResult.buffer.size === 0)
    assert(hogeError.buffer.size === 0)
    assert(fooError.buffer.size === 0)
    assert(nResult.buffer.size === 0)

    {
      val hoge = new Hoge()
      hoge.id.modify(1)
      val hoges = Seq(hoge)
      val foo = new Foo()
      foo.id.modify(10)
      foo.hogeId.modify(1)
      val foos = Seq(foo)
      fragment.add(Seq(hoges, foos))
      assert(hogeResult.buffer.size === 1)
      assert(hogeResult.buffer(0).id === hoge.id)
      assert(fooResult.buffer.size === 1)
      assert(fooResult.buffer(0).id === foo.id)
      assert(fooResult.buffer(0).hogeId === foo.hogeId)
      assert(hogeError.buffer.size === 0)
      assert(fooError.buffer.size === 0)
      assert(nResult.buffer.size === 1)
      assert(nResult.buffer(0).n.get === 10)
    }

    fragment.reset()
    assert(hogeResult.buffer.size === 0)
    assert(fooResult.buffer.size === 0)
    assert(hogeError.buffer.size === 0)
    assert(fooError.buffer.size === 0)
    assert(nResult.buffer.size === 0)

    {
      val hoge = new Hoge()
      hoge.id.modify(1)
      val hoges = Seq(hoge)
      val foos = (0 until 10).map { i =>
        val foo = new Foo()
        foo.id.modify(i)
        foo.hogeId.modify(1)
        foo
      }
      fragment.add(Seq(hoges, foos))
      assert(hogeResult.buffer.size === 0)
      assert(fooResult.buffer.size === 0)
      assert(hogeError.buffer.size === 1)
      assert(hogeError.buffer(0).id === hoge.id)
      assert(fooError.buffer.size === 10)
      fooError.buffer.zip(foos).foreach {
        case (actual, expected) =>
          assert(actual.id === expected.id)
          assert(actual.hogeId === expected.hogeId)
      }
      assert(nResult.buffer.size === 1)
      assert(nResult.buffer(0).n.get === 10)
    }

    fragment.reset()
    assert(hogeResult.buffer.size === 0)
    assert(fooResult.buffer.size === 0)
    assert(hogeError.buffer.size === 0)
    assert(fooError.buffer.size === 0)
    assert(nResult.buffer.size === 0)
  }
}

object CoGroupOperatorCompilerSpec {

  class Hoge extends DataModel[Hoge] {

    val id = new IntOption()

    override def reset(): Unit = {
      id.setNull()
    }
    override def copyFrom(other: Hoge): Unit = {
      id.copyFrom(other.id)
    }

    def getIdOption: IntOption = id
  }

  class Foo extends DataModel[Foo] {

    val id = new IntOption()
    val hogeId = new IntOption()

    override def reset(): Unit = {
      id.setNull()
      hogeId.setNull()
    }
    override def copyFrom(other: Foo): Unit = {
      id.copyFrom(other.id)
      hogeId.copyFrom(other.hogeId)
    }

    def getIdOption: IntOption = id
    def getHogeIdOption: IntOption = hogeId
  }

  class N extends DataModel[N] {

    val n = new IntOption()

    override def reset(): Unit = {
      n.setNull()
    }
    override def copyFrom(other: N): Unit = {
      n.copyFrom(other.n)
    }

    def getIOption: IntOption = n
  }

  class CoGroupOperator {

    private[this] val n = new N

    @CoGroup
    def cogroup(
      hogeList: JList[Hoge], fooList: JList[Foo],
      hogeResult: Result[Hoge], fooResult: Result[Foo],
      hogeError: Result[Hoge], fooError: Result[Foo],
      nResult: Result[N], n: Int): Unit = {
      if (hogeList.size == 1 && fooList.size == 1) {
        hogeResult.add(hogeList(0))
        fooResult.add(fooList(0))
      } else {
        hogeList.foreach(hogeError.add)
        fooList.foreach(fooError.add)
      }
      this.n.n.modify(n)
      nResult.add(this.n)
    }
  }
}
