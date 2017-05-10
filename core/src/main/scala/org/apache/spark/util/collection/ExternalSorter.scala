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

package org.apache.spark.util.collection

import java.io._
import java.util.Comparator

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable

import com.google.common.io.ByteStreams

import org.apache.spark._
import org.apache.spark.memory.TaskMemoryManager
import org.apache.spark.serializer._
import org.apache.spark.executor.ShuffleWriteMetrics
import org.apache.spark.storage.{BlockId, DiskBlockObjectWriter}

/**
  * Sorts and potentially merges a number of key-value pairs of type (K, V) to produce key-combiner
  * pairs of type (K, C). Uses a Partitioner to first group the keys into partitions, and then
  * optionally sorts keys within each partition using a custom Comparator. Can output a single
  * partitioned file with a different byte range for each partition, suitable for shuffle fetches.
  *
  * If combining is disabled, the type C must equal V -- we'll cast the objects at the end.
  *
  * Note: Although ExternalSorter is a fairly generic sorter, some of its configuration is tied
  * to its use in sort-based shuffle (for example, its block compression is controlled by
  * `spark.shuffle.compress`).  We may need to revisit this if ExternalSorter is used in other
  * non-shuffle contexts where we might want to use different configuration settings.
  *
  * @param aggregator  optional Aggregator with combine functions to use for merging data
  * @param partitioner optional Partitioner; if given, sort by partition ID and then key
  * @param ordering    optional Ordering to sort keys within each partition; should be a total ordering
  * @param serializer  serializer to use when spilling to disk
  *
  *                    Note that if an Ordering is given, we'll always sort using it, so only provide it if you really
  *                    want the output keys to be sorted. In a map task without map-side combine for example, you
  *                    probably want to pass None as the ordering to avoid extra sorting. On the other hand, if you do
  *                    want to do combining, having an Ordering is more efficient than not having it.
  *
  *                    Users interact with this class in the following way:
  *
  * 1. Instantiate an ExternalSorter.
  *
  * 2. Call insertAll() with a set of records.
  *
  * 3. Request an iterator() back to traverse sorted/aggregated records.
  *     - or -
  *                    Invoke writePartitionedFile() to create a file containing sorted/aggregated outputs
  *                    that can be used in Spark's sort shuffle.
  *
  *                    At a high level, this class works internally as follows:
  *
  *  - We repeatedly fill up buffers of in-memory data, using either a PartitionedAppendOnlyMap if
  *                    we want to combine by key, or a PartitionedPairBuffer if we don't.
  *                    Inside these buffers, we sort elements by partition ID and then possibly also by key.
  *                    To avoid calling the partitioner multiple times with each key, we store the partition ID
  *                    alongside each record.
  *
  *  - When each buffer reaches our memory limit, we spill it to a file. This file is sorted first
  *                    by partition ID and possibly second by key or by hash code of the key, if we want to do
  *    aggregation. For each file, we track how many objects were in each partition in memory, so we
  *                    don't have to write out the partition ID for every element.
  *
  *  - When the user requests an iterator or file output, the spilled files are merged, along with
  *                    any remaining in-memory data, using the same sort order defined above (unless both sorting
  *                    and aggregation are disabled). If we need to aggregate by key, we either use a total ordering
  *                    from the ordering parameter, or read the keys with the same hash code and compare them with
  *                    each other for equality to merge values.
  *
  *  - Users are expected to call stop() at the end to delete all the intermediate files.
  */
private[spark] class ExternalSorter[K, V, C](
                                              context: TaskContext,
                                              aggregator: Option[Aggregator[K, V, C]] = None,
                                              partitioner: Option[Partitioner] = None,
                                              ordering: Option[Ordering[K]] = None,
                                              serializer: Option[Serializer] = None)
  extends Logging
    with Spillable[WritablePartitionedPairCollection[K, C]] {

  override protected[this] def taskMemoryManager: TaskMemoryManager = context.taskMemoryManager()

  private val conf = SparkEnv.get.conf

  private val numPartitions = partitioner.map(_.numPartitions).getOrElse(1)
  private val shouldPartition = numPartitions > 1

  private def getPartition(key: K): Int = {
    if (shouldPartition) partitioner.get.getPartition(key) else 0
  }

  private val blockManager = SparkEnv.get.blockManager
  private val diskBlockManager = blockManager.diskBlockManager
  private val ser = Serializer.getSerializer(serializer)
  private val serInstance = ser.newInstance()

  // Use getSizeAsKb (not bytes) to maintain backwards compatibility if no units are provided
  private val fileBufferSize = conf.getSizeAsKb("spark.shuffle.file.buffer", "32k").toInt * 1024

  // Size of object batches when reading/writing from serializers.
  //
  // Objects are written in batches, with each batch using its own serialization stream. This
  // cuts down on the size of reference-tracking maps constructed when deserializing a stream.
  //
  // NOTE: Setting this too low can cause excessive copying when serializing, since some serializers
  // grow internal data structures by growing + copying every time the number of objects doubles.
  private val serializerBatchSize = conf.getLong("spark.shuffle.spill.batchSize", 10000)

  // Data structures to store in-memory objects before we spill. Depending on whether we have an
  // Aggregator set, we either put objects into an AppendOnlyMap where we combine them, or we
  // store them in an array buffer.
  private var map = new PartitionedAppendOnlyMap[K, C]
  private var buffer = new PartitionedPairBuffer[K, C]

  // Total spilling statistics
  private var _diskBytesSpilled = 0L

  def diskBytesSpilled: Long = _diskBytesSpilled

  // Peak size of the in-memory data structure observed so far, in bytes
  private var _peakMemoryUsedBytes: Long = 0L

  def peakMemoryUsedBytes: Long = _peakMemoryUsedBytes

  // A comparator for keys K that orders them within a partition to allow aggregation or sorting.
  // Can be a partial ordering by hash code if a total ordering is not provided through by the
  // user. (A partial ordering means that equal keys have comparator.compare(k, k) = 0, but some
  // non-equal keys also have this, so we need to do a later pass to find truly equal keys).
  // Note that we ignore this if no aggregator and no ordering are given.
  private val keyComparator: Comparator[K] = ordering.getOrElse(
    new Comparator[K] {
      //比较key的hash值
      override def compare(a: K, b: K): Int = {
        val h1 = if (a == null) 0 else a.hashCode()
        val h2 = if (b == null) 0 else b.hashCode()
        if (h1 < h2) -1 else if (h1 == h2) 0 else 1
      }
    })

  private def comparator: Option[Comparator[K]] = {
    if (ordering.isDefined || aggregator.isDefined) {
      Some(keyComparator)
    } else {
      None
    }
  }

  // Information about a spilled file. Includes sizes in bytes of "batches" written by the
  // serializer as we periodically reset its stream, as well as number of elements in each
  // partition, used to efficiently keep track of partitions when merging.
  private[this] case class SpilledFile(
                                        file: File,
                                        blockId: BlockId,
                                        serializerBatchSizes: Array[Long],
                                        elementsPerPartition: Array[Long])

  private val spills = new ArrayBuffer[SpilledFile]

  /**
    * Number of files this sorter has spilled so far.
    * Exposed for testing.
    */
  private[spark] def numSpills: Int = spills.size

  def insertAll(records: Iterator[Product2[K, V]]): Unit = {
    // TODO: stop combining if we find that the reduction factor isn't high
    val shouldCombine = aggregator.isDefined

    //首先判断是否需要进行Map端的combine操作
    if (shouldCombine) {
      // Combine values in-memory first using our AppendOnlyMap
      //如果需要进行Map端combine操作，使用PartitionedAppendOnlyMap作为缓存
      // mergeValue其实就是我们用reduceByKey的时候参数值，即_+_
      val mergeValue = aggregator.get.mergeValue
      //(C, V) => C  =>func
      //createCombiner其实是和mergeValue一样的，都是_+_，但是在使用createCombiner中只传了一个参数，所以另一个参数默认采用默认值，这里为0
      val createCombiner = aggregator.get.createCombiner
      //V => C
      var kv: Product2[K, V] = null
      // 这个update也是一个函数，逻辑为如果某个Key已经存在记录record就使用上面获取的聚合函数进行聚合操作，
      // 如果不存在记录，就使用createCombiner方法进行初始化操作
      val update = (hadValue: Boolean, oldValue: C) => {
        if (hadValue) mergeValue(oldValue, kv._2) else createCombiner(kv._2)
      }
      //遍历所有的records
      while (records.hasNext) {
        //记录spill的频率，每当read一条record的时候就会记录一次
        addElementsRead()
        //使用kv存储当前读的records
        kv = records.next()
        //对key相同的记录的value进行合并
        //更新map中的数据，其中private var map = new PartitionedAppendOnlyMap[K, C]，
        // 如果需要进行combine就将数据放在map中，然后在map中对数据进行更新操作
        map.changeValue((getPartition(kv._1), kv._1), update)
        //判断是否需要进行spill操作
        maybeSpillCollection(usingMap = true)
      }
    } else {
      // Stick values into our buffer
      while (records.hasNext) {
        addElementsRead()
        val kv = records.next()
        buffer.insert(getPartition(kv._1), kv._1, kv._2.asInstanceOf[C])
        maybeSpillCollection(usingMap = false)
      }
    }
  }

  /**
    * Spill the current in-memory collection to disk if needed.
    *
    * @param usingMap whether we're using a map or buffer as our current in-memory collection
    */
  private def maybeSpillCollection(usingMap: Boolean): Unit = {
    var estimatedSize = 0L
    if (usingMap) {
      //先估计Map有可能占有的内存空间
      estimatedSize = map.estimateSize()
      if (maybeSpill(map, estimatedSize)) {
        map = new PartitionedAppendOnlyMap[K, C]
      }
    } else {
      estimatedSize = buffer.estimateSize()
      if (maybeSpill(buffer, estimatedSize)) {
        buffer = new PartitionedPairBuffer[K, C]
      }
    }

    //这个变量由内部测量系统收集内存占用信息
    if (estimatedSize > _peakMemoryUsedBytes) {
      _peakMemoryUsedBytes = estimatedSize
    }
  }

  /**
    * Spill our in-memory collection to a sorted file that we can merge later.
    * We add this file into `spilledFiles` to find it later.
    *
    * @param collection whichever collection we're using (map or buffer)
    */
  override protected[this] def spill(collection: WritablePartitionedPairCollection[K, C]): Unit = {
    // Because these files may be read during shuffle, their compression must be controlled by
    // spark.shuffle.compress instead of spark.shuffle.spill.compress, so we need to use
    // createTempShuffleBlock here; see SPARK-3426 for more context.
    //创建临时的Block，和临时的file
    val (blockId, file) = diskBlockManager.createTempShuffleBlock()

    // These variables are reset after each flush
    var objectsWritten: Long = 0
    var spillMetrics: ShuffleWriteMetrics = null
    var writer: DiskBlockObjectWriter = null

    def openWriter(): Unit = {
      assert(writer == null && spillMetrics == null)
      spillMetrics = new ShuffleWriteMetrics
      writer = blockManager.getDiskWriter(blockId, file, serInstance, fileBufferSize, spillMetrics)
    }

    //创建一个DiskWriter
    openWriter()

    // List of batch sizes (bytes) in the order they are written to disk
    val batchSizes = new ArrayBuffer[Long]

    // How many elements we have in each partition
    val elementsPerPartition = new Array[Long](numPartitions)

    // Flush the disk writer's contents to disk, and update relevant variables.
    // The writer is closed at the end of this process, and cannot be reused.
    def flush(): Unit = {
      val w = writer
      writer = null
      w.commitAndClose()
      _diskBytesSpilled += spillMetrics.shuffleBytesWritten
      batchSizes.append(spillMetrics.shuffleBytesWritten)
      spillMetrics = null
      objectsWritten = 0
    }

    var success = false
    try {
      //把数据重新按key值中的partitionId来排序，这里的key是一个tuple (partitionId,K)
      val it = collection.destructiveSortedWritablePartitionedIterator(comparator)
      while (it.hasNext) {
        val partitionId = it.nextPartition()
        require(partitionId >= 0 && partitionId < numPartitions,
          s"partition Id: ${partitionId} should be in the range [0, ${numPartitions})")
        it.writeNext(writer)
        elementsPerPartition(partitionId) += 1
        objectsWritten += 1
        //当写的对象达到一定个数时，就spill到另一个文件中
        if (objectsWritten == serializerBatchSize) {
          flush()
          openWriter()
        }
      }
      if (objectsWritten > 0) {
        flush()
      } else if (writer != null) {
        val w = writer
        writer = null
        //处理异常没有flush的数据
        w.revertPartialWritesAndClose()
      }
      success = true
    } finally {
      if (!success) {
        // This code path only happens if an exception was thrown above before we set success;
        // close our stuff and let the exception be thrown further
        if (writer != null) {
          writer.revertPartialWritesAndClose()
        }
        if (file.exists()) {
          if (!file.delete()) {
            logWarning(s"Error deleting ${file}")
          }
        }
      }
    }
    //最终把写的文件插入到spills中
    spills.append(SpilledFile(file, blockId, batchSizes.toArray, elementsPerPartition))
  }

  /**
    * Merge a sequence of sorted files, giving an iterator over partitions and then over elements
    * inside each partition. This can be used to either write out a new file or return data to
    * the user.
    *
    * Returns an iterator over all the data written to this object, grouped by partition. For each
    * partition we then have an iterator over its contents, and these are expected to be accessed
    * in order (you can't "skip ahead" to one partition without reading the previous one).
    * Guaranteed to return a key-value pair for each partition, in order of partition ID.
    */
  private def merge(spills: Seq[SpilledFile], inMemory: Iterator[((Int, K), C)])
  : Iterator[(Int, Iterator[Product2[K, C]])] = {
    //过构建SpillReader可以通过readNextPartition得到Iterator，
    // 通过Iterater又可以获取每条记录的信息，这里其实是生成了一个SpillReader列表
    val readers = spills.map(new SpillReader(_))
    //通过使用buffered方法，构建一个带BufferedIterator接口的Iterator
    // 获取next值的时候，获取的是(key,value)的值
    val inMemBuffered = inMemory.buffered
    (0 until numPartitions).iterator.map { p =>
      //内存中的数据可以通过构建IteratorForPartition来获取当前partition下的Iterator
      val inMemIterator = new IteratorForPartition(p, inMemBuffered)
      // 如果都是 Iterator那就可以合并操作了
      val iterators = readers.map(_.readNextPartition()) ++ Seq(inMemIterator)
      //在前面构建Sorter的时候其实我们已经定义了aggregator，所以由此可以看出map端的combile还会在根据key值聚合一次
      if (aggregator.isDefined) {
        // Perform partial aggregation across partitions
        //所以就直接进到这里面来啦
        (p, mergeWithAggregation(
          iterators, aggregator.get.mergeCombiners, keyComparator, ordering.isDefined))
      } else if (ordering.isDefined) {
        // No aggregator given, but we have an ordering (e.g. used by reduce tasks in sortByKey);
        // sort the elements without trying to merge them
        (p, mergeSort(iterators, ordering.get))
      } else {
        (p, iterators.iterator.flatten)
      }
    }
  }

  /**
    * Merge-sort a sequence of (K, C) iterators using a given a comparator for the keys.
    */
  private def mergeSort(iterators: Seq[Iterator[Product2[K, C]]], comparator: Comparator[K])
  : Iterator[Product2[K, C]] = {
    val bufferedIters = iterators.filter(_.hasNext).map(_.buffered)
    type Iter = BufferedIterator[Product2[K, C]]
    //这里主要是PriorityQueue中的compare方法来实现的，因为前面我们是按照把spill的数据按照key值进行了排序，
    // 所以PriorityQueue操作的各个Iterater其实都是有顺序的，
    // 所以在使用dequeue方法时正好可以获得其最小key值对应的数据。这里对key 值的排序其实是按照它的hashCode进行排序的。
    val heap = new mutable.PriorityQueue[Iter]()(new Ordering[Iter] {
      // Use the reverse of comparator.compare because PriorityQueue dequeues the max
      override def compare(x: Iter, y: Iter): Int = -comparator.compare(x.head._1, y.head._1)
    })
    heap.enqueue(bufferedIters: _*) // Will contain only the iterators with hasNext = true
    new Iterator[Product2[K, C]] {
      override def hasNext: Boolean = !heap.isEmpty

      override def next(): Product2[K, C] = {
        if (!hasNext) {
          throw new NoSuchElementException
        }
        val firstBuf = heap.dequeue()
        val firstPair = firstBuf.next()
        if (firstBuf.hasNext) {
          heap.enqueue(firstBuf)
        }
        firstPair
      }
    }
  }

  /**
    * Merge a sequence of (K, C) iterators by aggregating values for each key, assuming that each
    * iterator is sorted by key with a given comparator. If the comparator is not a total ordering
    * (e.g. when we sort objects by hash code and different keys may compare as equal although
    * they're not), we still merge them by doing equality tests for all keys that compare as equal.
    */
  private def mergeWithAggregation(
                                    iterators: Seq[Iterator[Product2[K, C]]],
                                    mergeCombiners: (C, C) => C,
                                    comparator: Comparator[K],
                                    totalOrder: Boolean)
  : Iterator[Product2[K, C]] = {
    if (!totalOrder) {
      // We only have a partial ordering, e.g. comparing the keys by hash code, which means that
      // multiple distinct keys might be treated as equal by the ordering. To deal with this, we
      // need to read all keys considered equal by the ordering at once and compare them.
      //这个iterators参数指的是spills和map中的iterators的集合，
      // 再进行完mergeSort方法后，在调用next的时候，它会根据iterators操作的数据按key生序来提取数据，记住这一步尤其重要。
      //buffered方法就是提供了一个可以查看这群iterators的head的方法
      new Iterator[Iterator[Product2[K, C]]] {
        val sorted = mergeSort(iterators, comparator).buffered

        // Buffers reused across elements to decrease memory allocation
        val keys = new ArrayBuffer[K]
        val combiners = new ArrayBuffer[C]

        override def hasNext: Boolean = sorted.hasNext

        override def next(): Iterator[Product2[K, C]] = {
          if (!hasNext) {
            throw new NoSuchElementException
          }
          keys.clear()
          combiners.clear()
          val firstPair = sorted.next()
          keys += firstPair._1
          combiners += firstPair._2
          val key = firstPair._1
          //然后就是对key值的value，进行聚合操作操作了，它这里实现的特别巧妙，
          // 它会根据前面通过mergeSort生成的Iterater分别去查找每一个spill和map中Iterater去获取该key值的value，
          // 如果有，则进行聚合。关于这里的这个比较器也是比较的key的hashcode，
          // 所以这样的话每一次就能获得每个partition下的所有（key，value）数据了
          while (sorted.hasNext && comparator.compare(sorted.head._1, key) == 0) {
            val pair = sorted.next()
            var i = 0
            var foundKey = false
            while (i < keys.size && !foundKey) {
              if (keys(i) == pair._1) {
                combiners(i) = mergeCombiners(combiners(i), pair._2)
                foundKey = true
              }
              i += 1
            }
            if (!foundKey) {
              keys += pair._1
              combiners += pair._2
            }
          }

          // Note that we return an iterator of elements since we could've had many keys marked
          // equal by the partial order; we flatten this below to get a flat iterator of (K, C).
          keys.iterator.zip(combiners.iterator)
        }

        //关于这个flatMap操作就是把操作各个partition数据的iterater连接到一起
      }.flatMap(i => i)
    } else {
      // We have a total ordering, so the objects with the same key are sequential.
      new Iterator[Product2[K, C]] {
        val sorted = mergeSort(iterators, comparator).buffered

        override def hasNext: Boolean = sorted.hasNext

        override def next(): Product2[K, C] = {
          if (!hasNext) {
            throw new NoSuchElementException
          }
          val elem = sorted.next()
          val k = elem._1
          var c = elem._2
          while (sorted.hasNext && sorted.head._1 == k) {
            val pair = sorted.next()
            c = mergeCombiners(c, pair._2)
          }
          (k, c)
        }
      }
    }
  }

  /**
    * An internal class for reading a spilled file partition by partition. Expects all the
    * partitions to be requested in order.
    */
  private[this] class SpillReader(spill: SpilledFile) {
    // Serializer batch offsets; size will be batchSize.length + 1
    val batchOffsets = spill.serializerBatchSizes.scanLeft(0L)(_ + _)

    // Track which partition and which batch stream we're in. These will be the indices of
    // the next element we will read. We'll also store the last partition read so that
    // readNextPartition() can figure out what partition that was from.
    var partitionId = 0
    var indexInPartition = 0L
    var batchId = 0
    var indexInBatch = 0
    var lastPartitionId = 0

    skipToNextPartition()

    // Intermediate file and deserializer streams that read from exactly one batch
    // This guards against pre-fetching and other arbitrary behavior of higher level streams
    var fileStream: FileInputStream = null
    var deserializeStream = nextBatchStream() // Also sets fileStream

    var nextItem: (K, C) = null
    var finished = false

    /** Construct a stream that only reads from the next batch */
    def nextBatchStream(): DeserializationStream = {
      // Note that batchOffsets.length = numBatches + 1 since we did a scan above; check whether
      // we're still in a valid batch.
      if (batchId < batchOffsets.length - 1) {
        if (deserializeStream != null) {
          deserializeStream.close()
          fileStream.close()
          deserializeStream = null
          fileStream = null
        }

        val start = batchOffsets(batchId)
        fileStream = new FileInputStream(spill.file)
        fileStream.getChannel.position(start)
        batchId += 1

        val end = batchOffsets(batchId)

        assert(end >= start, "start = " + start + ", end = " + end +
          ", batchOffsets = " + batchOffsets.mkString("[", ", ", "]"))

        val bufferedStream = new BufferedInputStream(ByteStreams.limit(fileStream, end - start))
        val compressedStream = blockManager.wrapForCompression(spill.blockId, bufferedStream)
        serInstance.deserializeStream(compressedStream)
      } else {
        // No more batches left
        cleanup()
        null
      }
    }

    /**
      * Update partitionId if we have reached the end of our current partition, possibly skipping
      * empty partitions on the way.
      */
    private def skipToNextPartition() {
      while (partitionId < numPartitions &&
        indexInPartition == spill.elementsPerPartition(partitionId)) {
        partitionId += 1
        indexInPartition = 0L
      }
    }

    /**
      * Return the next (K, C) pair from the deserialization stream and update partitionId,
      * indexInPartition, indexInBatch and such to match its location.
      *
      * If the current batch is drained, construct a stream for the next batch and read from it.
      * If no more pairs are left, return null.
      */
    private def readNextItem(): (K, C) = {
      if (finished || deserializeStream == null) {
        return null
      }
      val k = deserializeStream.readKey().asInstanceOf[K]
      val c = deserializeStream.readValue().asInstanceOf[C]
      lastPartitionId = partitionId
      // Start reading the next batch if we're done with this one
      indexInBatch += 1
      if (indexInBatch == serializerBatchSize) {
        indexInBatch = 0
        deserializeStream = nextBatchStream()
      }
      // Update the partition location of the element we're reading
      indexInPartition += 1
      skipToNextPartition()
      // If we've finished reading the last partition, remember that we're done
      if (partitionId == numPartitions) {
        finished = true
        if (deserializeStream != null) {
          deserializeStream.close()
        }
      }
      (k, c)
    }

    var nextPartitionToRead = 0

    def readNextPartition(): Iterator[Product2[K, C]] = new Iterator[Product2[K, C]] {
      val myPartition = nextPartitionToRead
      nextPartitionToRead += 1

      override def hasNext: Boolean = {
        if (nextItem == null) {
          nextItem = readNextItem()
          if (nextItem == null) {
            return false
          }
        }
        assert(lastPartitionId >= myPartition)
        // Check that we're still in the right partition; note that readNextItem will have returned
        // null at EOF above so we would've returned false there
        lastPartitionId == myPartition
      }

      override def next(): Product2[K, C] = {
        if (!hasNext) {
          throw new NoSuchElementException
        }
        val item = nextItem
        nextItem = null
        item
      }
    }

    // Clean up our open streams and put us in a state where we can't read any more data
    def cleanup() {
      batchId = batchOffsets.length // Prevent reading any other batch
      val ds = deserializeStream
      deserializeStream = null
      fileStream = null
      ds.close()
      // NOTE: We don't do file.delete() here because that is done in ExternalSorter.stop().
      // This should also be fixed in ExternalAppendOnlyMap.
    }
  }

  /**
    * Return an iterator over all the data written to this object, grouped by partition and
    * aggregated by the requested aggregator. For each partition we then have an iterator over its
    * contents, and these are expected to be accessed in order (you can't "skip ahead" to one
    * partition without reading the previous one). Guaranteed to return a key-value pair for each
    * partition, in order of partition ID.
    *
    * For now, we just merge all the spilled files in once pass, but this can be modified to
    * support hierarchical merging.
    * Exposed for testing.
    */
  def partitionedIterator: Iterator[(Int, Iterator[Product2[K, C]])] = {
    val usingMap = aggregator.isDefined
    val collection: WritablePartitionedPairCollection[K, C] = if (usingMap) map else buffer
    if (spills.isEmpty) {
      // Special case: if we have only in-memory data, we don't need to merge streams, and perhaps
      // we don't even need to sort by anything other than partition ID
      if (!ordering.isDefined) {
        // The user hasn't requested sorted keys, so only sort by partition ID, not key
        groupByPartition(collection.partitionedDestructiveSortedIterator(None))
      } else {
        // We do need to sort by both partition ID and key
        groupByPartition(collection.partitionedDestructiveSortedIterator(Some(keyComparator)))
      }
    } else {
      // Merge spilled and in-memory data
      //通过调用merge方法来实现把上文中spillFile中的数据和当前map中的数据进行merge
      merge(spills, collection.partitionedDestructiveSortedIterator(comparator))
    }
  }

  /**
    * Return an iterator over all the data written to this object, aggregated by our aggregator.
    */
  def iterator: Iterator[Product2[K, C]] = partitionedIterator.flatMap(pair => pair._2)

  /**
    * Write all the data added into this ExternalSorter into a file in the disk store. This is
    * called by the SortShuffleWriter.
    *
    * @param blockId block ID to write to. The index file will be blockId.name + ".index".
    * @return array of lengths, in bytes, of each partition of the file (used by map output tracker)
    */
  def writePartitionedFile(
                            blockId: BlockId,
                            outputFile: File): Array[Long] = {

    // Track location of each range in the output file
    val lengths = new Array[Long](numPartitions)
    //首先判断是否有数据已经spill到磁盘了
    if (spills.isEmpty) {
      //当只有内存中有数据时
      // Case where we only have in-memory data
      val collection = if (aggregator.isDefined) map else buffer
      // 获得迭代器
      val it = collection.destructiveSortedWritablePartitionedIterator(comparator)
      //进行迭代并将数据写到磁盘
      while (it.hasNext) {
        val writer = blockManager.getDiskWriter(blockId, outputFile, serInstance, fileBufferSize, context.taskMetrics.shuffleWriteMetrics.get)
        val partitionId = it.nextPartition()
        while (it.hasNext && it.nextPartition() == partitionId) {
          //把同一个分区的记录写到一块 并返回该blockId中该partitionId的长度
          it.writeNext(writer)
        }
        writer.commitAndClose()
        val segment = writer.fileSegment()
        //最后返回的是每个partition写入数据的长度
        lengths(partitionId) = segment.length
      }
    } else {
      //需要持久化到硬盘中的情况
      // We must perform merge-sort; get an iterator by partition and write everything directly.
      //.在调用partitionedIterator方法后，会对应的partition中的已经combine过的数据
      for ((id, elements) <- this.partitionedIterator) {
        if (elements.hasNext) {
          val writer = blockManager.getDiskWriter(blockId, outputFile, serInstance, fileBufferSize,
            context.taskMetrics.shuffleWriteMetrics.get)
          //将partition中的数据写入data文件
          for (elem <- elements) {
            writer.write(elem._1, elem._2)
          }
          writer.commitAndClose()
          val segment = writer.fileSegment()
          //最后返回的是每个partition写入数据的长度
          lengths(id) = segment.length
        }
      }
    }

    //更改task测量系统中的参数值
    context.taskMetrics().incMemoryBytesSpilled(memoryBytesSpilled)
    context.taskMetrics().incDiskBytesSpilled(diskBytesSpilled)
    context.internalMetricsToAccumulators(
      InternalAccumulator.PEAK_EXECUTION_MEMORY).add(peakMemoryUsedBytes)

    lengths
  }

  def stop(): Unit = {
    map = null // So that the memory can be garbage-collected
    buffer = null // So that the memory can be garbage-collected
    spills.foreach(s => s.file.delete())
    spills.clear()
    releaseMemory()
  }

  /**
    * Given a stream of ((partition, key), combiner) pairs *assumed to be sorted by partition ID*,
    * group together the pairs for each partition into a sub-iterator.
    *
    * @param data an iterator of elements, assumed to already be sorted by partition ID
    */
  private def groupByPartition(data: Iterator[((Int, K), C)])
  : Iterator[(Int, Iterator[Product2[K, C]])] = {
    val buffered = data.buffered
    (0 until numPartitions).iterator.map(p => (p, new IteratorForPartition(p, buffered)))
  }

  /**
    * An iterator that reads only the elements for a given partition ID from an underlying buffered
    * stream, assuming this partition is the next one to be read. Used to make it easier to return
    * partitioned iterators from our in-memory collection.
    */
  private[this] class IteratorForPartition(partitionId: Int, data: BufferedIterator[((Int, K), C)])
    extends Iterator[Product2[K, C]] {
    override def hasNext: Boolean = data.hasNext && data.head._1._1 == partitionId

    override def next(): Product2[K, C] = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      val elem = data.next()
      (elem._1._2, elem._2)
    }
  }

}
