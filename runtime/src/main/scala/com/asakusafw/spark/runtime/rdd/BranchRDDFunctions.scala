package com.asakusafw.spark.runtime.rdd

import scala.reflect.ClassTag

import org.apache.spark._
import org.apache.spark.rdd._

import com.asakusafw.spark.runtime.orderings.NoOrdering

case class BranchKey(id: Int)
case class Branch[K](branchKey: BranchKey, actualKey: K)

class BranchRDDFunctions[T: ClassTag](self: RDD[T]) extends Serializable {

  def branch[K, U](
    branchKeys: Set[BranchKey],
    f: Iterator[T] => Iterator[(Branch[K], U)],
    partitioners: Map[BranchKey, Partitioner] = Map.empty[BranchKey, Partitioner],
    keyOrderings: Map[BranchKey, Ordering[K]] = Map.empty[BranchKey, Ordering[K]],
    preservesPartitioning: Boolean = false): Map[BranchKey, RDD[(K, U)]] = {

    val prepared = self.mapPartitions(f, preservesPartitioning)

    val branchPartitioner = new BranchPartitioner(
      branchKeys,
      partitioners.withDefaultValue(IdentityPartitioner(prepared.partitions.size)))
    val shuffled = new ShuffledRDD[Branch[K], U, U](prepared, branchPartitioner)
    if (keyOrderings.nonEmpty) {
      shuffled.setKeyOrdering(
        new BranchKeyOrdering(keyOrderings.withDefaultValue(new NoOrdering[K])))
    }
    val branched = shuffled.map { case (Branch(_, k), u) => (k, u) }
    branchKeys.map {
      case branch =>
        branch -> new BranchedRDD[(K, U)](
          branched,
          partitioners.get(branch).orElse(prepared.partitioner),
          i => {
            val offset = branchPartitioner.offsetOf(branch)
            val numPartitions = branchPartitioner.numPartitionsOf(branch)
            offset <= i && i < offset + numPartitions
          })
    }.toMap
  }
}

private class BranchPartitioner(branchKeys: Set[BranchKey], partitioners: Map[BranchKey, Partitioner])
    extends Partitioner {

  private[this] val branches = branchKeys.toSeq.sortBy(_.hashCode)

  private[this] val offsets = branches.scanLeft(0)(_ + numPartitionsOf(_))

  override val numPartitions: Int = offsets.last

  override def getPartition(key: Any): Int = {
    assert(key.isInstanceOf[(_, _)],
      s"The key used for branch should be the form (branchKey, actualKey): ${key}")
    val Branch(branchKey, actualKey) = key.asInstanceOf[Branch[Any]]
    offsetOf(branchKey) + partitioners(branchKey).getPartition(actualKey)
  }

  val offsetOf: Map[BranchKey, Int] = branches.zip(offsets).toMap

  def numPartitionsOf(branch: BranchKey): Int = partitioners(branch).numPartitions
}

private class BranchKeyOrdering[K](orderings: Map[BranchKey, Ordering[K]])
    extends Ordering[Branch[K]] {

  override def compare(left: Branch[K], right: Branch[K]): Int = {
    orderings(left.branchKey).compare(left.actualKey, right.actualKey)
  }
}

private class BranchedRDD[T: ClassTag](
  @transient prev: RDD[T],
  @transient part: Option[Partitioner],
  @transient partitionFilterFunc: Int => Boolean)
    extends PartitionPruningRDD[T](prev, partitionFilterFunc) {

  override val partitioner = part
}
