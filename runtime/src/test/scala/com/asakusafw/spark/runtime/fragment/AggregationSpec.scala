package com.asakusafw.spark.runtime
package fragment

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AggregationSpecTest extends AggregationSpec

class AggregationSpec extends FlatSpec {

  import AggregationSpec._

  behavior of classOf[Aggregation[_, _, _]].getSimpleName

  it should "combine values" in {
    val aggregation = new TestAggregation()
    val combiner = aggregation.valueCombiner()
    (0 until 100).foreach { i =>
      combiner.insert(i % 2 == 0, i)
    }
    val combined = combiner.toSeq.sortBy(_._1)
    assert(combined.size === 2)
    assert(combined(0)._1 === false)
    assert(combined(0)._2 === (1 until 100 by 2))
    assert(combined(1)._1 === true)
    assert(combined(1)._2 === (0 until 100 by 2))
  }

  it should "combine combiners" in {
    val aggregation = new TestAggregation()
    val combiner = aggregation.combinerCombiner()
    (0 until 100).foreach { i =>
      combiner.insert(i % 2 == 0, Seq(i))
    }
    val combined = combiner.toSeq.sortBy(_._1)
    assert(combined.size === 2)
    assert(combined(0)._1 === false)
    assert(combined(0)._2 === (1 until 100 by 2))
    assert(combined(1)._1 === true)
    assert(combined(1)._2 === (0 until 100 by 2))
  }
}

object AggregationSpec {

  class TestAggregation extends Aggregation[Boolean, Int, Seq[Int]] {

    override def mapSideCombine: Boolean = true

    override lazy val isSpillEnabled = false

    override def createCombiner(value: Int): Seq[Int] = {
      Seq(value)
    }

    override def mergeValue(combiner: Seq[Int], value: Int): Seq[Int] = {
      combiner :+ value
    }

    override def mergeCombiners(comb1: Seq[Int], comb2: Seq[Int]): Seq[Int] = {
      comb1 ++ comb2
    }
  }
}