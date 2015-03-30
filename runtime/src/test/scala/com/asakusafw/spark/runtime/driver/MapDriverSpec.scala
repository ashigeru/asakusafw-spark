package com.asakusafw.spark.runtime
package driver

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import org.apache.hadoop.conf.Configuration
import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd._

import com.asakusafw.runtime.model.DataModel
import com.asakusafw.runtime.value.IntOption
import com.asakusafw.spark.runtime.fragment._

@RunWith(classOf[JUnitRunner])
class MapDriverSpecTest extends MapDriverSpec

class MapDriverSpec extends FlatSpec with SparkSugar {

  import MapDriverSpec._

  behavior of classOf[MapDriver[_, _]].getSimpleName

  it should "map" in {
    val hoges = sc.parallelize(0 until 10).map { i =>
      val hoge = new Hoge()
      hoge.id.modify(i)
      ((), hoge)
    }.asInstanceOf[RDD[(_, Hoge)]]

    val driver = new TestMapDriver(sc, hadoopConf, hoges)

    val outputs = driver.execute()
    outputs.mapValues(_.collect.toSeq).foreach {
      case ("hogeResult", values) =>
        val hogeResult = values.asInstanceOf[Seq[(_, Hoge)]].map(_._2)
        assert(hogeResult.size === 10)
        assert(hogeResult.map(_.id.get) === (0 until 10))
      case ("fooResult", values) =>
        val fooResult = values.asInstanceOf[Seq[(_, Foo)]].map(_._2)
        assert(fooResult.size === 45)
        assert(fooResult.map(foo => (foo.id.get, foo.hogeId.get)) ===
          (for {
            i <- (0 until 10)
            j <- (0 until i)
          } yield {
            ((i * (i - 1)) / 2 + j, i)
          }))
    }
  }
}

object MapDriverSpec {

  class TestMapDriver(
    @transient sc: SparkContext,
    @transient hadoopConf: Broadcast[Configuration],
    @transient prev: RDD[(_, Hoge)])
      extends MapDriver[Hoge, String](sc, hadoopConf, Seq(prev)) {

    override def name = "TestMap"

    override def branchKeys: Set[String] = Set("hogeResult", "fooResult")

    override def partitioners: Map[String, Partitioner] = Map.empty

    override def orderings[K]: Map[String, Ordering[K]] = Map.empty

    override def aggregations: Map[String, Aggregation[_, _, _]] = Map.empty

    override def fragments[U <: DataModel[U]]: (Fragment[Hoge], Map[String, OutputFragment[U]]) = {
      val outputs = Map(
        "hogeResult" -> new HogeOutputFragment,
        "fooResult" -> new FooOutputFragment)
      val fragment = new TestFragment(outputs)
      (fragment, outputs.asInstanceOf[Map[String, OutputFragment[U]]])
    }

    override def shuffleKey[U](branch: String, value: DataModel[_]): U = {
      value.asInstanceOf[U]
    }
  }

  class Hoge extends DataModel[Hoge] {

    val id = new IntOption()

    override def reset(): Unit = {
      id.setNull()
    }
    override def copyFrom(other: Hoge): Unit = {
      id.copyFrom(other.id)
    }
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
  }

  class HogeOutputFragment extends OutputFragment[Hoge] {
    override def newDataModel: Hoge = new Hoge()
  }

  class FooOutputFragment extends OutputFragment[Foo] {
    override def newDataModel: Foo = new Foo()
  }

  class TestFragment(outputs: Map[String, Fragment[_]]) extends Fragment[Hoge] {

    private val foo = new Foo()

    override def add(hoge: Hoge): Unit = {
      outputs("hogeResult").asInstanceOf[HogeOutputFragment].add(hoge)
      for (i <- 0 until hoge.id.get) {
        foo.reset()
        foo.id.modify((hoge.id.get * (hoge.id.get - 1)) / 2 + i)
        foo.hogeId.copyFrom(hoge.id)
        outputs("fooResult").asInstanceOf[FooOutputFragment].add(foo)
      }
    }

    override def reset(): Unit = {
      outputs.values.foreach(_.reset())
    }
  }
}