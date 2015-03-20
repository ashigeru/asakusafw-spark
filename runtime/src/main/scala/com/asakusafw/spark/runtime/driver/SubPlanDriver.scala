package com.asakusafw.spark.runtime.driver

import org.apache.hadoop.io.Writable
import org.apache.spark._
import org.apache.spark.rdd.RDD

import com.asakusafw.runtime.model.DataModel

trait SubPlanDriver[B] extends Serializable {

  def sc: SparkContext

  def name: String

  def execute(): Map[B, RDD[(_, _)]]
}
