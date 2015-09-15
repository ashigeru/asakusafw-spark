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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.io.{ DataInput, DataOutput }

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.apache.hadoop.io.Writable
import org.apache.spark.broadcast.Broadcast

import com.asakusafw.lang.compiler.api.CompilerOptions
import com.asakusafw.lang.compiler.api.testing.MockJobflowProcessorContext
import com.asakusafw.lang.compiler.model.description._
import com.asakusafw.lang.compiler.model.testing.OperatorExtractor
import com.asakusafw.runtime.core.Result
import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value._
import com.asakusafw.spark.compiler.spi.{ OperatorCompiler, OperatorType }
import com.asakusafw.spark.compiler.subplan.{ BranchKeysClassBuilder, BroadcastIdsClassBuilder }
import com.asakusafw.spark.runtime.driver.BroadcastId
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.tools.asm._
import com.asakusafw.vocabulary.operator.Convert

@RunWith(classOf[JUnitRunner])
class ConvertOperatorCompilerSpecTest extends ConvertOperatorCompilerSpec

class ConvertOperatorCompilerSpec extends FlatSpec with LoadClassSugar with TempDir with CompilerContext {

  import ConvertOperatorCompilerSpec._

  behavior of classOf[ConvertOperatorCompiler].getSimpleName

  it should "compile Convert operator" in {
    val operator = OperatorExtractor
      .extract(classOf[Convert], classOf[ConvertOperator], "convert")
      .input("input", ClassDescription.of(classOf[InputModel]))
      .output("original", ClassDescription.of(classOf[InputModel]))
      .output("out", ClassDescription.of(classOf[OutputModel]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    val classpath = createTempDirectory("ConvertOperatorCompilerSpec").toFile
    implicit val context = newContext("flowId", classpath)

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    val cls = loadClass(thisType.getClassName, classpath).asSubclass(classOf[Fragment[InputModel]])

    val out1 = new GenericOutputFragment[InputModel]
    val out2 = new GenericOutputFragment[OutputModel]

    val fragment = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]]).newInstance(Map.empty, out1, out2)
    fragment.reset()

    val dm = new InputModel()
    for (i <- 0 until 10) {
      dm.i.modify(i)
      dm.l.modify(i)
      fragment.add(dm)
    }
    out1.iterator.zipWithIndex.foreach {
      case (dm, i) =>
        assert(dm.i.get === i)
        assert(dm.l.get === i)
    }
    out2.iterator.zipWithIndex.foreach {
      case (dm, i) =>
        assert(dm.l.get === 10 * i)
    }
    fragment.reset()
  }

  it should "compile Convert operator with projective model" in {
    val operator = OperatorExtractor
      .extract(classOf[Convert], classOf[ConvertOperator], "convertp")
      .input("input", ClassDescription.of(classOf[InputModel]))
      .output("original", ClassDescription.of(classOf[InputModel]))
      .output("out", ClassDescription.of(classOf[OutputModel]))
      .argument("n", ImmediateDescription.of(10))
      .build()

    val classpath = createTempDirectory("ConvertOperatorCompilerSpec").toFile
    implicit val context = newContext("flowId", classpath)

    val thisType = OperatorCompiler.compile(operator, OperatorType.ExtractType)
    val cls = loadClass(thisType.getClassName, classpath).asSubclass(classOf[Fragment[InputModel]])

    val out1 = new GenericOutputFragment[InputModel]
    val out2 = new GenericOutputFragment[OutputModel]

    val fragment = cls.getConstructor(
      classOf[Map[BroadcastId, Broadcast[_]]],
      classOf[Fragment[_]], classOf[Fragment[_]]).newInstance(Map.empty, out1, out2)

    fragment.reset()
    val dm = new InputModel()
    for (i <- 0 until 10) {
      dm.i.modify(i)
      dm.l.modify(i)
      fragment.add(dm)
    }
    out1.iterator.zipWithIndex.foreach {
      case (dm, i) =>
        assert(dm.i.get === i)
        assert(dm.l.get === i)
    }
    out2.iterator.zipWithIndex.foreach {
      case (dm, i) =>
        assert(dm.l.get === 10 * i)
    }

    fragment.reset()
  }
}

object ConvertOperatorCompilerSpec {

  trait InputP {
    def getIOption: IntOption
    def getLOption: LongOption
  }

  class InputModel extends DataModel[InputModel] with InputP with Writable {

    val i: IntOption = new IntOption()
    val l: LongOption = new LongOption()

    override def reset: Unit = {
      i.setNull()
      l.setNull()
    }
    override def copyFrom(other: InputModel): Unit = {
      i.copyFrom(other.i)
      l.copyFrom(other.l)
    }
    override def readFields(in: DataInput): Unit = {
      i.readFields(in)
      l.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      i.write(out)
      l.write(out)
    }

    def getIOption: IntOption = i
    def getLOption: LongOption = l
  }

  class OutputModel extends DataModel[OutputModel] with Writable {

    val l: LongOption = new LongOption()

    override def reset: Unit = {
      l.setNull()
    }
    override def copyFrom(other: OutputModel): Unit = {
      l.copyFrom(other.l)
    }
    override def readFields(in: DataInput): Unit = {
      l.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      l.write(out)
    }

    def getLOption: LongOption = l
  }

  class ConvertOperator {

    private[this] val out = new OutputModel()

    @Convert
    def convert(in: InputModel, n: Int): OutputModel = {
      out.reset()
      out.l.modify(n * in.l.get)
      out
    }

    @Convert
    def convertp[I <: InputP](in: I, n: Int): OutputModel = {
      out.reset()
      out.getLOption.modify(n * in.getLOption.get)
      out
    }
  }
}