/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.util.Random

import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.serializer.Serializer
import org.apache.spark.shuffle.hash.HashShuffleManager
import org.apache.spark.shuffle.sort.SortShuffleManager
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.errors.attachTree
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.util.MutablePair

/**
 * Performs a shuffle that will result in the desired `newPartitioning`.
 */
case class Exchange(
    var newPartitioning: Partitioning,
    child: SparkPlan,
    @transient coordinator: Option[ExchangeCoordinator]) extends UnaryNode {

  override def nodeName: String = {
    val extraInfo = coordinator match {
      case Some(exchangeCoordinator) if exchangeCoordinator.isEstimated =>
        s"(coordinator id: ${System.identityHashCode(coordinator)})"
      case Some(exchangeCoordinator) if !exchangeCoordinator.isEstimated =>
        s"(coordinator id: ${System.identityHashCode(coordinator)})"
      case None => ""
    }

    val simpleNodeName = if (tungstenMode) "TungstenExchange" else "Exchange"
    s"$simpleNodeName$extraInfo"
  }

  /**
   * Returns true iff we can support the data type, and we are not doing range partitioning.
   */
  private lazy val tungstenMode: Boolean = !newPartitioning.isInstanceOf[RangePartitioning]

  override def outputPartitioning: Partitioning = newPartitioning

  override def output: Seq[Attribute] = child.output

  // This setting is somewhat counterintuitive:
  // If the schema works with UnsafeRow, then we tell the planner that we don't support safe row,
  // so the planner inserts a converter to convert data into UnsafeRow if needed.
  override def outputsUnsafeRows: Boolean = tungstenMode
  override def canProcessSafeRows: Boolean = !tungstenMode
  override def canProcessUnsafeRows: Boolean = tungstenMode

  /**
   * Determines whether records must be defensively copied before being sent to the shuffle.
   * Several of Spark's shuffle components will buffer deserialized Java objects in memory. The
   * shuffle code assumes that objects are immutable and hence does not perform its own defensive
   * copying. In Spark SQL, however, operators' iterators return the same mutable `Row` object. In
   * order to properly shuffle the output of these operators, we need to perform our own copying
   * prior to sending records to the shuffle. This copying is expensive, so we try to avoid it
   * whenever possible. This method encapsulates the logic for choosing when to copy.
   *
   * In the long run, we might want to push this logic into core's shuffle APIs so that we don't
   * have to rely on knowledge of core internals here in SQL.
   *
   * See SPARK-2967, SPARK-4479, and SPARK-7375 for more discussion of this issue.
   *
   * @param partitioner the partitioner for the shuffle
   * @param serializer the serializer that will be used to write rows
   * @return true if rows should be copied before being shuffled, false otherwise
   */
  private def needToCopyObjectsBeforeShuffle(
      partitioner: Partitioner,
      serializer: Serializer): Boolean = {
    // Note: even though we only use the partitioner's `numPartitions` field, we require it to be
    // passed instead of directly passing the number of partitions in order to guard against
    // corner-cases where a partitioner constructed with `numPartitions` partitions may output
    // fewer partitions (like RangePartitioner, for example).
    val conf = child.sqlContext.sparkContext.conf
    val shuffleManager = SparkEnv.get.shuffleManager
    val sortBasedShuffleOn = shuffleManager.isInstanceOf[SortShuffleManager]
    val bypassMergeThreshold = conf.getInt("spark.shuffle.sort.bypassMergeThreshold", 200)
    if (sortBasedShuffleOn) {
      val bypassIsSupported = SparkEnv.get.shuffleManager.isInstanceOf[SortShuffleManager]
      if (bypassIsSupported && partitioner.numPartitions <= bypassMergeThreshold) {
        // If we're using the original SortShuffleManager and the number of output partitions is
        // sufficiently small, then Spark will fall back to the hash-based shuffle write path, which
        // doesn't buffer deserialized records.
        // Note that we'll have to remove this case if we fix SPARK-6026 and remove this bypass.
        false
      } else if (serializer.supportsRelocationOfSerializedObjects) {
        // SPARK-4550 and  SPARK-7081 extended sort-based shuffle to serialize individual records
        // prior to sorting them. This optimization is only applied in cases where shuffle
        // dependency does not specify an aggregator or ordering and the record serializer has
        // certain properties. If this optimization is enabled, we can safely avoid the copy.
        //
        // Exchange never configures its ShuffledRDDs with aggregators or key orderings, so we only
        // need to check whether the optimization is enabled and supported by our serializer.
        false
      } else {
        // Spark's SortShuffleManager uses `ExternalSorter` to buffer records in memory, so we must
        // copy.
        true
      }
    } else if (shuffleManager.isInstanceOf[HashShuffleManager]) {
      // We're using hash-based shuffle, so we don't need to copy.
      false
    } else {
      // Catch-all case to safely handle any future ShuffleManager implementations.
      true
    }
  }

  @transient private lazy val sparkConf = child.sqlContext.sparkContext.getConf

  private val serializer: Serializer = {
    if (tungstenMode) {
      new UnsafeRowSerializer(child.output.size)
    } else {
      new SparkSqlSerializer(sparkConf)
    }
  }

  override protected def doPrepare(): Unit = {
    // If an ExchangeCoordinator is needed, we register this Exchange operator
    // to the coordinator when we do prepare. It is important to make sure
    // we register this operator right before the execution instead of register it
    // in the constructor because it is possible that we create new instances of
    // Exchange operators when we transform the physical plan
    // (then the ExchangeCoordinator will hold references of unneeded Exchanges).
    // So, we should only call registerExchange just before we start to execute
    // the plan.
    //需要注册一下个Exchange。
    coordinator match {
      case Some(exchangeCoordinator) => exchangeCoordinator.registerExchange(this)
      case None =>
    }
  }

  /**
   * Returns a [[ShuffleDependency]] that will partition rows of its child based on
   * the partitioning scheme defined in `newPartitioning`. Those partitions of
   * the returned ShuffleDependency will be the input of shuffle.
   */
  private[sql] def prepareShuffleDependency(): ShuffleDependency[Int, InternalRow, InternalRow] = {
    //首先先获得前面依赖的RDD
    val rdd = child.execute()
    val part: Partitioner = newPartitioning match {
      case RoundRobinPartitioning(numPartitions) => new HashPartitioner(numPartitions)
        //然后再构建我们使用的Partitioner，这里以HashPartitioning为例，这里就构造了这个HashPartitioner(numPartitions)
      case HashPartitioning(expressions, numPartitions) => new HashPartitioner(numPartitions)
      case RangePartitioning(sortingExpressions, numPartitions) =>
        // Internally, RangePartitioner runs a job on the RDD that samples keys to compute
        // partition bounds. To get accurate samples, we need to copy the mutable keys.
        val rddForSampling = rdd.mapPartitionsInternal { iter =>
          val mutablePair = new MutablePair[InternalRow, Null]()
          iter.map(row => mutablePair.update(row.copy(), null))
        }
        // We need to use an interpreted ordering here because generated orderings cannot be
        // serialized and this ordering needs to be created on the driver in order to be passed into
        // Spark core code.
        implicit val ordering = new InterpretedOrdering(sortingExpressions, child.output)
        new RangePartitioner(numPartitions, rddForSampling, ascending = true)
      case SinglePartition =>
        new Partitioner {
          override def numPartitions: Int = 1
          override def getPartition(key: Any): Int = 0
        }
      case _ => sys.error(s"Exchange not implemented for $newPartitioning")
      // TODO: Handle BroadcastPartitioning.
    }
    //这里就是获取一个Partition的key的提取器
    def getPartitionKeyExtractor(): InternalRow => Any = newPartitioning match {
      case RoundRobinPartitioning(numPartitions) =>
        // Distributes elements evenly across output partitions, starting from a random partition.
        var position = new Random(TaskContext.get().partitionId()).nextInt(numPartitions)
        (row: InternalRow) => {
          // The HashPartitioner will handle the `mod` by the number of partitions
          position += 1
          position
        }
      case HashPartitioning(expressions, _) => newMutableProjection(expressions, child.output)()
      case RangePartitioning(_, _) | SinglePartition => identity
      case _ => sys.error(s"Exchange not implemented for $newPartitioning")
    }
    //然后接下来就是根据上面那个方法获取每一条记录的key，改变partition中数据的类型为（partitionID，row）
    val rddWithPartitionIds: RDD[Product2[Int, InternalRow]] = {
      // needToCopyObjectsBeforeShuffle是很重要的一个方法，下面就是根据下面的生成新的RDD
      if (needToCopyObjectsBeforeShuffle(part, serializer)) {
        rdd.mapPartitionsInternal { iter =>
          val getPartitionKey = getPartitionKeyExtractor()
          iter.map { row => (part.getPartition(getPartitionKey(row)), row.copy()) }
        }
      } else {
        rdd.mapPartitionsInternal { iter =>
          val getPartitionKey = getPartitionKeyExtractor()
          val mutablePair = new MutablePair[Int, InternalRow]()
          iter.map { row => mutablePair.update(part.getPartition(getPartitionKey(row)), row) }
        }
      }
    }

    // Now, we manually create a ShuffleDependency. Because pairs in rddWithPartitionIds
    // are in the form of (partitionId, row) and every partitionId is in the expected range
    // [0, part.numPartitions - 1]. The partitioner of this is a PartitionIdPassthrough.
    val dependency =
    //最终就构建了这个dependency，这里注意这个partitioner可不是HashPartioner，不过他的数据是通过HashPartioner来构建的。
      new ShuffleDependency[Int, InternalRow, InternalRow](
        rddWithPartitionIds,
        //这里的这个PartitionIdPassthrough只是一个最简单的Partioner。
        new PartitionIdPassthrough(part.numPartitions),
        Some(serializer))

    dependency
  }

  /**
   * Returns a [[ShuffledRowRDD]] that represents the post-shuffle dataset.
   * This [[ShuffledRowRDD]] is created based on a given [[ShuffleDependency]] and an optional
   * partition start indices array. If this optional array is defined, the returned
   * [[ShuffledRowRDD]] will fetch pre-shuffle partitions based on indices of this array.
   */
  private[sql] def preparePostShuffleRDD(
      shuffleDependency: ShuffleDependency[Int, InternalRow, InternalRow],
      specifiedPartitionStartIndices: Option[Array[Int]] = None): ShuffledRowRDD = {
    // If an array of partition start indices is provided, we need to use this array
    // to create the ShuffledRowRDD. Also, we need to update newPartitioning to
    // update the number of post-shuffle partitions.
    //这里如果这个specifiedPartitionStartIndices中是有值的话，则需要指定一个新的newPartitioning，
    // 也就是前面估计的时候，有些分区的值是超过那个targetPostShuffleInputSize的值的，也就是有一个过大的partition，
    // 这样的话，在接下来就会根据这个信息，来对partition进行合并。（它这里的处理是保证数据均衡)
    specifiedPartitionStartIndices.foreach { indices =>
      assert(newPartitioning.isInstanceOf[HashPartitioning])
      newPartitioning = UnknownPartitioning(indices.length)
    }
    new ShuffledRowRDD(shuffleDependency, specifiedPartitionStartIndices)
  }

  protected override def doExecute(): RDD[InternalRow] = attachTree(this , "execute") {
    coordinator match {
        //为上面有使用exchangeCoordinator
      case Some(exchangeCoordinator) =>
        val shuffleRDD = exchangeCoordinator.postShuffleRDD(this)
        assert(shuffleRDD.partitions.length == newPartitioning.numPartitions)
        shuffleRDD
      case None =>
        val shuffleDependency = prepareShuffleDependency()
        preparePostShuffleRDD(shuffleDependency)
    }
  }
}

object Exchange {
  def apply(newPartitioning: Partitioning, child: SparkPlan): Exchange = {
    Exchange(newPartitioning, child, coordinator = None: Option[ExchangeCoordinator])
  }
}

/**
 * Ensures that the [[org.apache.spark.sql.catalyst.plans.physical.Partitioning Partitioning]]
 * of input data meets the
 * [[org.apache.spark.sql.catalyst.plans.physical.Distribution Distribution]] requirements for
 * each operator by inserting [[Exchange]] Operators where required.  Also ensure that the
 * input partition ordering requirements are met.
 */
private[sql] case class EnsureRequirements(sqlContext: SQLContext) extends Rule[SparkPlan] {
  private def defaultNumPreShufflePartitions: Int = sqlContext.conf.numShufflePartitions

  private def targetPostShuffleInputSize: Long = sqlContext.conf.targetPostShuffleInputSize

  private def adaptiveExecutionEnabled: Boolean = sqlContext.conf.adaptiveExecutionEnabled

  private def minNumPostShufflePartitions: Option[Int] = {
    val minNumPostShufflePartitions = sqlContext.conf.minNumPostShufflePartitions
    if (minNumPostShufflePartitions > 0) Some(minNumPostShufflePartitions) else None
  }

  /**
   * Given a required distribution, returns a partitioning that satisfies that distribution.
   */
  private def createPartitioning(
      requiredDistribution: Distribution,
      numPartitions: Int): Partitioning = {
    requiredDistribution match {
      case AllTuples => SinglePartition
      case ClusteredDistribution(clustering) => HashPartitioning(clustering, numPartitions)
      case OrderedDistribution(ordering) => RangePartitioning(ordering, numPartitions)
      case dist => sys.error(s"Do not know how to satisfy distribution $dist")
    }
  }

  /**
   * Adds [[ExchangeCoordinator]] to [[Exchange]]s if adaptive query execution is enabled
   * and partitioning schemes of these [[Exchange]]s support [[ExchangeCoordinator]].
   */
  private def withExchangeCoordinator(
      children: Seq[SparkPlan],
      requiredChildDistributions: Seq[Distribution]): Seq[SparkPlan] = {
    //先判断它能否支持ExchangeCoordinator
    val supportsCoordinator =
      if (children.exists(_.isInstanceOf[Exchange])) {
        // Right now, ExchangeCoordinator only support HashPartitionings.
        //也就是children中必须含有Exchange，且必须保证它的输出partitioning都是HashPartitioning类型的
        children.forall {
          case e @ Exchange(hash: HashPartitioning, _, _) => true
          case child =>
            child.outputPartitioning match {
              case hash: HashPartitioning => true
              case collection: PartitioningCollection =>
                collection.partitionings.forall(_.isInstanceOf[HashPartitioning])
              case _ => false
            }
        }
      } else {
        // In this case, although we do not have Exchange operators, we may still need to
        // shuffle data when we have more than one children because data generated by
        // these children may not be partitioned in the same way.
        // Please see the comment in withCoordinator for more details.
        //第二种情况就是，children 中shuffle的数据没有使用同一种方式来进行分区
        // requiredChildDistributions.forall(_.isInstanceOf[ClusteredDistribution])
        val supportsDistribution =
          requiredChildDistributions.forall(_.isInstanceOf[ClusteredDistribution])
        children.length > 1 && supportsDistribution
      }

    //这里注意如果使用这个Coordinator的话，还需要打开其开关，默认情况下是关闭的，即spark.sql.adaptive.enabled
    val withCoordinator =
      if (adaptiveExecutionEnabled && supportsCoordinator) {
        //下面就是创建这个coordinator
        val coordinator =
          new ExchangeCoordinator(
            children.length,
            targetPostShuffleInputSize,
            minNumPostShufflePartitions)
        children.zip(requiredChildDistributions).map {
          //如果child是Exchange，则直接将这个coordinator赋值给它
          case (e: Exchange, _) =>
            // This child is an Exchange, we need to add the coordinator.
            e.copy(coordinator = Some(coordinator))
          case (child, distribution) =>
            // If this child is not an Exchange, we need to add an Exchange for now.
            // Ideally, we can try to avoid this Exchange. However, when we reach here,
            // there are at least two children operators (because if there is a single child
            // and we can avoid Exchange, supportsCoordinator will be false and we
            // will not reach here.). Although we can make two children have the same number of
            // post-shuffle partitions. Their numbers of pre-shuffle partitions may be different.
            // For example, let's say we have the following plan
            //         Join
            //         /  \
            //       Agg  Exchange
            //       /      \
            //    Exchange  t2
            //      /
            //     t1
            // In this case, because a post-shuffle partition can include multiple pre-shuffle
            // partitions, a HashPartitioning will not be strictly partitioned by the hashcodes
            // after shuffle. So, even we can use the child Exchange operator of the Join to
            // have a number of post-shuffle partitions that matches the number of partitions of
            // Agg, we cannot say these two children are partitioned in the same way.
            // Here is another case
            //         Join
            //         /  \
            //       Agg1  Agg2
            //       /      \
            //   Exchange1  Exchange2
            //       /       \
            //      t1       t2
            // In this case, two Aggs shuffle data with the same column of the join condition.
            // After we use ExchangeCoordinator, these two Aggs may not be partitioned in the same
            // way. Let's say that Agg1 and Agg2 both have 5 pre-shuffle partitions and 2
            // post-shuffle partitions. It is possible that Agg1 fetches those pre-shuffle
            // partitions by using a partitionStartIndices [0, 3]. However, Agg2 may fetch its
            // pre-shuffle partitions by using another partitionStartIndices [0, 4].
            // So, Agg1 and Agg2 are actually not co-partitioned.
            //
            // It will be great to introduce a new Partitioning to represent the post-shuffle
            // partitions when one post-shuffle partition includes multiple pre-shuffle partitions.
            //关于上面的注释，理解起来其实很简单，就是这个child的获得的数据可能不是使用同一种partitioning来获得的，所以这里重新给它构造一个Exchange，使得在这个child进行操作的时候，要操作的数据是按照同样的partitioning来分区的数据
            //所以这里的实现就是再给这个child创建一个新的Exchange操作。
            val targetPartitioning =
              createPartitioning(distribution, defaultNumPreShufflePartitions)
            assert(targetPartitioning.isInstanceOf[HashPartitioning])
            Exchange(targetPartitioning, child, Some(coordinator))
        }
      } else {
        // If we do not need ExchangeCoordinator, the original children are returned.
        children
      }

    withCoordinator
  }

  private def ensureDistributionAndOrdering(operator: SparkPlan): SparkPlan = {
    val requiredChildDistributions: Seq[Distribution] = operator.requiredChildDistribution
    val requiredChildOrderings: Seq[Seq[SortOrder]] = operator.requiredChildOrdering
    var children: Seq[SparkPlan] = operator.children
    assert(requiredChildDistributions.length == children.length)
    assert(requiredChildOrderings.length == children.length)

    // Ensure that the operator's children satisfy their output distribution requirements:
    // child物理计划的输出数据分布是否满足当前物理计划的数据分布要求。
    // 例如：当前的操作LeftSemiJoinHash，而它的Distribution为ClusteredDistribution，它的child的Distribution为UnspecifiedDistribution，
    // 则会执行下面的else，即给这个child添加一个Exchange操作。
    children = children.zip(requiredChildDistributions).map { case (child, distribution) =>
      if (child.outputPartitioning.satisfies(distribution)) {
        child
      } else {
        Exchange(createPartitioning(distribution, defaultNumPreShufflePartitions), child)
      }
    }

    // If the operator has multiple children and specifies child output distributions (e.g. join),
    // then the children's output partitionings must be compatible:
    //这里就是上面提到的第二个原因，需要添加Exchange操作：对于包含2个Child物理计划的情况，2个Child物理计划的输出数据有可能不compatile。
    // 因为涉及到两个Child物理计划采用相同的Shuffle运算方法才能够保证在本物理计划执行的时候一个分区的数据在一个节点，
    // 所以2个Child物理计划的输出数据必须采用compatile的分区输出算法。如果不compatile需要创建Exchange替换掉Child物理计划。
    if (children.length > 1
        && requiredChildDistributions.toSet != Set(UnspecifiedDistribution)
        && !Partitioning.allCompatible(children.map(_.outputPartitioning))) {

      // First check if the existing partitions of the children all match. This means they are
      // partitioned by the same partitioning into the same number of partitions. In that case,
      // don't try to make them match `defaultPartitions`, just use the existing partitioning.
      //获取Children下最大的partition的数量
      val maxChildrenNumPartitions = children.map(_.outputPartitioning.numPartitions).max
      //检查children的partition数量是否都是这个值，换句话说，也就是各个child的Partitioning是否是一样的
      val useExistingPartitioning = children.zip(requiredChildDistributions).forall {
        case (child, distribution) => {
          child.outputPartitioning.guarantees(
            createPartitioning(distribution, maxChildrenNumPartitions))
        }
      }

      //如果是一致的则不需要shuffle child的输出
      children = if (useExistingPartitioning) {
        // We do not need to shuffle any child's output.
        children
      } else {
        // We need to shuffle at least one child's output.
        // Now, we will determine the number of partitions that will be used by created
        // partitioning schemes.
        val numPartitions = {
          // Let's see if we need to shuffle all child's outputs when we use
          // maxChildrenNumPartitions.
          //检查一下是否需要shuffle 所有的child的输出
          val shufflesAllChildren = children.zip(requiredChildDistributions).forall {
            case (child, distribution) => {
              !child.outputPartitioning.guarantees(
                createPartitioning(distribution, maxChildrenNumPartitions))
            }
          }
          // If we need to shuffle all children, we use defaultNumPreShufflePartitions as the
          // number of partitions. Otherwise, we use maxChildrenNumPartitions.
          //最终的分区数，如果需要shuffle 所有的child，则使用默认分区数，如果仅需要shuffle 部分child，则使用上面得到的最大分区数。
          if (shufflesAllChildren) defaultNumPreShufflePartitions else maxChildrenNumPartitions
        }

        children.zip(requiredChildDistributions).map {
          case (child, distribution) => {
            val targetPartitioning =
              createPartitioning(distribution, numPartitions)
            //这里需要主要的是如果child的outputPartitioning和最终的Partitioning一致的话，就不需要添加exchange（这个其实就是对应的部分shuffle的情况），否则则添加
            if (child.outputPartitioning.guarantees(targetPartitioning)) {
              child
            } else {
              child match {
                // If child is an exchange, we replace it with
                // a new one having targetPartitioning.
                  //关于这个child如果本身就是一个Exchange的话，就直接更改它的Partitioning，如果不是则添加一个Exchange操作
                case Exchange(_, c, _) => Exchange(targetPartitioning, c)
                case _ => Exchange(targetPartitioning, child)
              }
            }
          }
        }
      }
    }

    // Now, we need to add ExchangeCoordinator if necessary.
    // Actually, it is not a good idea to add ExchangeCoordinators while we are adding Exchanges.
    // However, with the way that we plan the query, we do not have a place where we have a
    // global picture of all shuffle dependencies of a post-shuffle stage. So, we add coordinator
    // at here for now.
    // Once we finish https://issues.apache.org/jira/browse/SPARK-10665,
    // we can first add Exchanges and then add coordinator once we have a DAG of query fragments.
    children = withExchangeCoordinator(children, requiredChildDistributions)

    // Now that we've performed any necessary shuffles, add sorts to guarantee output orderings:
    //是否需要对shuffles进行排序，如果需要的话，则再添加一个Sort操作，
    children = children.zip(requiredChildOrderings).map { case (child, requiredOrdering) =>
      if (requiredOrdering.nonEmpty) {
        // If child.outputOrdering is [a, b] and requiredOrdering is [a], we do not need to sort.
        if (requiredOrdering != child.outputOrdering.take(requiredOrdering.length)) {
          Sort(requiredOrdering, global = false, child = child)
        } else {
          child
        }
      } else {
        child
      }
    }

    //然后就是新增的这些操作添加进来，这里就是TreeNode的操作，也就是树的操作
    operator.withNewChildren(children)
  }

  //里注意这个transformUp方法，其实它是和LogicalPlan中的resolveOperators方法是功能是一样的，
  // 只不过它没有对处理过的子节点的检查，都是来遍历这个treeNode，先操作其子节点，再对它自己进行操作。
  def apply(plan: SparkPlan): SparkPlan = plan.transformUp {
    case operator: SparkPlan => ensureDistributionAndOrdering(operator)
  }
}
