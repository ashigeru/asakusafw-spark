package com.asakusafw.spark.runtime
package driver

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import java.io.{ DataInput, DataOutput, File }

import scala.reflect.ClassTag

import org.apache.hadoop.io.Writable
import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.rdd._

import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value.IntOption
import com.asakusafw.spark.runtime.fragment._
import com.asakusafw.spark.runtime.orderings._

@RunWith(classOf[JUnitRunner])
class InputOutputDriverSpecTest extends InputOutputDriverSpec

class InputOutputDriverSpec extends FlatSpec with SparkSugar {

  import InputOutputDriverSpec._

  behavior of "Input/OutputDriver"

  it should "output and input" in {
    val tmpDir = File.createTempFile("test-", null)
    tmpDir.delete
    val path = tmpDir.getAbsolutePath

    val hoges = sc.parallelize(0 until 10).map { i =>
      val hoge = new Hoge()
      hoge.id.modify(i)
      hoge
    }

    new TestOutputDriver(sc, hoges, path).execute()

    val inputs = new TestInputDriver(sc, path).execute()
    assert(inputs("hogeResult").collect.toSeq.map(_._2.asInstanceOf[Hoge].id.get) === (0 until 10))
  }
}

object InputOutputDriverSpec {

  class TestOutputDriver(
    @transient sc: SparkContext,
    @transient input: RDD[Hoge],
    val path: String)
      extends OutputDriver[Hoge](sc, input)

  class TestInputDriver(
    @transient sc: SparkContext,
    basePath: String)
      extends InputDriver[Hoge, String](sc) {

    override def paths: Set[String] = Set(basePath + "/part-*")

    override def branchKeys: Set[String] = {
      Set("hogeResult")
    }

    override def partitioners: Map[String, Partitioner] = Map.empty

    override def orderings[K]: Map[String, Ordering[K]] = Map.empty

    override def fragments[U <: DataModel[U]]: (Fragment[Hoge], Map[String, OutputFragment[U]]) = {
      val fragment = new HogeOutputFragment
      val outputs = Map("hogeResult" -> fragment.asInstanceOf[OutputFragment[U]])
      (fragment, outputs)
    }

    override def shuffleKey[T <: DataModel[T], U <: DataModel[U]](branch: String, value: T): U = {
      value.asInstanceOf[U]
    }
  }

  class Hoge extends DataModel[Hoge] with Writable {

    val id = new IntOption()

    override def reset(): Unit = {
      id.setNull()
    }
    override def copyFrom(other: Hoge): Unit = {
      id.copyFrom(other.id)
    }
    override def readFields(in: DataInput): Unit = {
      id.readFields(in)
    }
    override def write(out: DataOutput): Unit = {
      id.write(out)
    }
  }

  class HogeOutputFragment extends OutputFragment[Hoge] {
    override def newDataModel: Hoge = new Hoge()
  }
}