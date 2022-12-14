/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.log.remote

import kafka.cluster.Partition
import kafka.log._
import kafka.server._
import kafka.server.checkpoints.{LeaderEpochCheckpoint, LeaderEpochCheckpointFile}
import kafka.server.epoch.{EpochEntry, LeaderEpochFileCache}
import kafka.utils.{MockTime, TestUtils}
import org.apache.kafka.common.config.AbstractConfig
import org.apache.kafka.common.record._
import org.apache.kafka.common.utils.Utils
import org.apache.kafka.common.{TopicIdPartition, TopicPartition, Uuid}
import org.apache.kafka.server.log.remote.storage.RemoteStorageManager.IndexType
import org.apache.kafka.server.log.remote.storage._
import org.easymock.{CaptureType, EasyMock}
import org.easymock.EasyMock._
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.{AfterEach, Test}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

import java.io.{File, FileInputStream}
import java.nio.file.{Files, Paths}
import java.util.{Collections, Optional, Properties}
import java.{lang, util}
import scala.collection.{Seq, mutable}
import scala.jdk.CollectionConverters._

class RemoteLogManagerTest {

  val clusterId = "test-cluster-id"
  val brokerId = 0
  val topicPartition = new TopicPartition("test-topic", 0)
  val topicIdPartition = new TopicIdPartition(Uuid.randomUuid(), topicPartition)
  val time = new MockTime()
  val brokerTopicStats = new BrokerTopicStats
  val logsDir: String = Files.createTempDirectory("kafka-").toString
  val cache = new LeaderEpochFileCache(topicPartition, checkpoint())
  val rlmConfig: RemoteLogManagerConfig = createRLMConfig()

  @AfterEach
  def afterEach(): Unit = {
    Utils.delete(Paths.get(logsDir).toFile)
  }

  @Test
  def testRLMConfig(): Unit = {
    val key = "hello"
    val rlmmConfigPrefix = RemoteLogManagerConfig.DEFAULT_REMOTE_LOG_METADATA_MANAGER_CONFIG_PREFIX
    val props: Properties = new Properties()
    props.put(rlmmConfigPrefix + key, "world")
    props.put("remote.log.metadata.y", "z")

    val rlmConfig = createRLMConfig(props)
    val rlmmConfig = rlmConfig.remoteLogMetadataManagerProps()
    assertEquals(props.get(rlmmConfigPrefix + key), rlmmConfig.get(key))
    assertFalse(rlmmConfig.containsKey("remote.log.metadata.y"))
  }

  @Test
  def testFindHighestRemoteOffsetOnEmptyRemoteStorage(): Unit = {
    cache.assign(0, 0)
    cache.assign(1, 500)

    val log: Log = createMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache))

    val rlmmManager: RemoteLogMetadataManager = createNiceMock(classOf[RemoteLogMetadataManager])
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt()))
      .andReturn(Optional.empty()).anyTimes()

    replay(log, rlmmManager)
    val remoteLogManager = new RemoteLogManager(_ => Option(log),
      (_, _) => {}, rlmConfig, time, 1, clusterId, logsDir, brokerTopicStats) {
      override private[remote] def createRemoteLogMetadataManager(): RemoteLogMetadataManager = rlmmManager
    }
    assertEquals(-1L, remoteLogManager.findHighestRemoteOffset(topicIdPartition))
  }

  @Test
  def testFindHighestRemoteOffset(): Unit = {
    cache.assign(0, 0)
    cache.assign(1, 500)

    val log: Log = createMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache))

    val rlmmManager: RemoteLogMetadataManager = createNiceMock(classOf[RemoteLogMetadataManager])
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt())).andAnswer(() => {
      val epoch = getCurrentArgument[Int](1)
      if (epoch == 0) Optional.of(200) else Optional.empty()
    }).anyTimes()

    replay(log, rlmmManager)
    val remoteLogManager = new RemoteLogManager(_ => Option(log),
      (_, _) => {}, rlmConfig, time, 1, clusterId, logsDir, brokerTopicStats) {
      override private[remote] def createRemoteLogMetadataManager(): RemoteLogMetadataManager = rlmmManager
    }
    assertEquals(200L, remoteLogManager.findHighestRemoteOffset(topicIdPartition))
  }

  @Test
  def testFindNextSegmentMetadata(): Unit = {
    cache.assign(0, 0)
    cache.assign(1, 30)
    cache.assign(2, 100)

    val log: Log = createNiceMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    expect(log.remoteLogEnabled()).andReturn(true).anyTimes()

    val nextSegmentLeaderEpochs = new util.HashMap[Integer, lang.Long]
    nextSegmentLeaderEpochs.put(0, 0)
    nextSegmentLeaderEpochs.put(1, 30)
    nextSegmentLeaderEpochs.put(2, 100)
    val nextSegmentMetadata: RemoteLogSegmentMetadata =
      new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
        100, 199, -1L, brokerId, -1L, 1024, nextSegmentLeaderEpochs)
    val rlmmManager: RemoteLogMetadataManager = createNiceMock(classOf[RemoteLogMetadataManager])
    expect(rlmmManager.remoteLogSegmentMetadata(EasyMock.eq(topicIdPartition), anyInt(), anyLong()))
      .andAnswer(() => {
        val epoch = getCurrentArgument[Int](1)
        val nextOffset = getCurrentArgument[Long](2)
        if (epoch == 2 && nextOffset >= 100L && nextOffset <= 199L)
          Optional.of(nextSegmentMetadata)
        else
          Optional.empty()
      }).anyTimes()
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt()))
      .andReturn(Optional.empty()).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(topicIdPartition)).andReturn(Collections.emptyIterator()).anyTimes()

    val topic = topicIdPartition.topicPartition().topic()
    val partition: Partition = createMock(classOf[Partition])
    expect(partition.topic).andReturn(topic).anyTimes()
    expect(partition.topicPartition).andReturn(topicIdPartition.topicPartition()).anyTimes()
    expect(partition.log).andReturn(Option(log)).anyTimes()
    expect(partition.getLeaderEpoch).andReturn(0).anyTimes()
    replay(log, partition, rlmmManager)

    val remoteLogManager = new RemoteLogManager(_ => Option(log),
      (_, _) => {}, rlmConfig, time, 1, clusterId, logsDir, brokerTopicStats) {
      override private[remote] def createRemoteLogMetadataManager(): RemoteLogMetadataManager = rlmmManager
    }
    remoteLogManager.onLeadershipChange(Set(partition), Set(), Collections.singletonMap(topic, topicIdPartition.topicId()))

    val segmentLeaderEpochs = new util.HashMap[Integer, lang.Long]()
    segmentLeaderEpochs.put(0, 0)
    segmentLeaderEpochs.put(1, 30)
    // end offset is set to 99, the next offset to search in remote storage is 100
    var segmentMetadata = new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
      0, 99, -1L, brokerId, -1L, 1024, segmentLeaderEpochs)
    assertEquals(Option(nextSegmentMetadata), remoteLogManager.findNextSegmentMetadata(segmentMetadata))

    // end offset is set to 105, the next offset to search in remote storage is 106
    segmentMetadata = new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
      0, 105, -1L, brokerId, -1L, 1024, segmentLeaderEpochs)
    assertEquals(Option(nextSegmentMetadata), remoteLogManager.findNextSegmentMetadata(segmentMetadata))

    segmentMetadata = new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
      0, 200, -1L, brokerId, -1L, 1024, segmentLeaderEpochs)
    assertEquals(None, remoteLogManager.findNextSegmentMetadata(segmentMetadata))

    verify(log, partition, rlmmManager)
  }

  @Test
  def testAddAbortedTransactions(): Unit = {
    val baseOffset = 45
    val timeIdx = new TimeIndex(nonExistentTempFile(), baseOffset, maxIndexSize = 30 * 12)
    val txnIdx = new TransactionIndex(baseOffset, TestUtils.tempFile())
    val offsetIdx = new OffsetIndex(nonExistentTempFile(), baseOffset, maxIndexSize = 4 * 8)
    offsetIdx.append(baseOffset + 0, 0)
    offsetIdx.append(baseOffset + 1, 100)
    offsetIdx.append(baseOffset + 2, 200)
    offsetIdx.append(baseOffset + 3, 300)

    val rsmManager: ClassLoaderAwareRemoteStorageManager = createNiceMock(classOf[ClassLoaderAwareRemoteStorageManager])
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.OFFSET))).andReturn(new FileInputStream(offsetIdx.file)).anyTimes()
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.TIMESTAMP))).andReturn(new FileInputStream(timeIdx.file)).anyTimes()
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.TRANSACTION))).andReturn(new FileInputStream(txnIdx.file)).anyTimes()

    val records: Records = createNiceMock(classOf[Records])
    expect(records.sizeInBytes()).andReturn(150).anyTimes()
    val fetchDataInfo = FetchDataInfo(LogOffsetMetadata(baseOffset, Log.UnknownOffset, 0), records)

    var upperBoundOffsetCapture: Option[Long] = None

    replay(rsmManager, records)
    val remoteLogManager =
      new RemoteLogManager(_ => None, (_, _) => {}, rlmConfig, time, 1, clusterId, logsDir, brokerTopicStats) {
        override private[remote] def createRemoteStorageManager(): ClassLoaderAwareRemoteStorageManager = rsmManager
        override private[remote] def collectAbortedTransactions(startOffset: Long,
                                                                upperBoundOffset: Long,
                                                                segmentMetadata: RemoteLogSegmentMetadata,
                                                                accumulator: List[AbortedTxn] => Unit): Unit = {
          upperBoundOffsetCapture = Option(upperBoundOffset)
        }
      }

    val segmentMetadata = new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
      45, 99, -1L, brokerId, -1L, 1024, Collections.singletonMap(0, 45))

    // If base-offset=45 and fetch-size=150, then the upperBoundOffset=47
    val actualFetchDataInfo = remoteLogManager.addAbortedTransactions(baseOffset, segmentMetadata, fetchDataInfo)
    assertTrue(actualFetchDataInfo.abortedTransactions.isDefined)
    assertTrue(actualFetchDataInfo.abortedTransactions.get.isEmpty)
    assertEquals(Option(47), upperBoundOffsetCapture)

    // If base-offset=45 and fetch-size=301, then the entry won't exists in the offset index, returns next
    // remote/local segment base offset.
    upperBoundOffsetCapture = None
    reset(records)
    expect(records.sizeInBytes()).andReturn(301).anyTimes()
    replay(records)
    remoteLogManager.addAbortedTransactions(baseOffset, segmentMetadata, fetchDataInfo)
    assertEquals(Option(100), upperBoundOffsetCapture)
  }

  @Test
  def testCollectAbortedTransactionsIteratesNextRemoteSegment(): Unit = {
    cache.assign(0, 0)

    val log: Log = createNiceMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    expect(log.logSegments).andReturn(Iterable.empty).anyTimes()
    expect(log.remoteLogEnabled()).andReturn(true).anyTimes()

    val baseOffset = 45
    val timeIdx = new TimeIndex(nonExistentTempFile(), baseOffset, maxIndexSize = 30 * 12)
    val txnIdx = new TransactionIndex(baseOffset, TestUtils.tempFile())
    val offsetIdx = new OffsetIndex(nonExistentTempFile(), baseOffset, maxIndexSize = 4 * 8)
    offsetIdx.append(baseOffset + 0, 0)
    offsetIdx.append(baseOffset + 1, 100)
    offsetIdx.append(baseOffset + 2, 200)
    offsetIdx.append(baseOffset + 3, 300)

    val nextTxnIdx = new TransactionIndex(100L, TestUtils.tempFile())
    val abortedTxns = List(
      new AbortedTxn(producerId = 0L, firstOffset = 50, lastOffset = 105, lastStableOffset = 60),
      new AbortedTxn(producerId = 1L, firstOffset = 55, lastOffset = 120, lastStableOffset = 100)
    )
    abortedTxns.foreach(nextTxnIdx.append)

    val nextSegmentMetadata: RemoteLogSegmentMetadata =
      new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
        100, 199, -1L, brokerId, -1L, 1024, Collections.singletonMap(0, 100))
    val rlmmManager: RemoteLogMetadataManager = createNiceMock(classOf[RemoteLogMetadataManager])
    expect(rlmmManager.remoteLogSegmentMetadata(EasyMock.eq(topicIdPartition), anyInt(), anyLong()))
      .andAnswer(() => {
        val epoch = getCurrentArgument[Int](1)
        val nextOffset = getCurrentArgument[Long](2)
        if (epoch == 0 && nextOffset >= 100L && nextOffset <= 199L)
          Optional.of(nextSegmentMetadata)
        else
          Optional.empty()
      }).anyTimes()
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt()))
      .andReturn(Optional.empty()).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(topicIdPartition)).andReturn(Collections.emptyIterator()).anyTimes()

    val rsmManager: ClassLoaderAwareRemoteStorageManager = createNiceMock(classOf[ClassLoaderAwareRemoteStorageManager])
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.OFFSET))).andReturn(new FileInputStream(offsetIdx.file)).anyTimes()
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.TIMESTAMP))).andReturn(new FileInputStream(timeIdx.file)).anyTimes()
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.TRANSACTION))).andAnswer(() => {
      val segmentMetadata = getCurrentArgument[RemoteLogSegmentMetadata](0)
      if (segmentMetadata.equals(nextSegmentMetadata)) {
        new FileInputStream(nextTxnIdx.file)
      } else {
        new FileInputStream(txnIdx.file)
      }
    }).anyTimes()

    val records: Records = createNiceMock(classOf[Records])
    expect(records.sizeInBytes()).andReturn(301).anyTimes()
    val fetchDataInfo = FetchDataInfo(LogOffsetMetadata(baseOffset, Log.UnknownOffset, 0), records)

    val topic = topicIdPartition.topicPartition().topic()
    val partition: Partition = createMock(classOf[Partition])
    expect(partition.topic).andReturn(topic).anyTimes()
    expect(partition.topicPartition).andReturn(topicIdPartition.topicPartition()).anyTimes()
    expect(partition.log).andReturn(Option(log)).anyTimes()
    expect(partition.getLeaderEpoch).andReturn(0).anyTimes()

    replay(log, rlmmManager, rsmManager, records, partition)
    val remoteLogManager =
      new RemoteLogManager(_ => Option(log), (_, _) => {}, rlmConfig, time, 1, clusterId, logsDir, brokerTopicStats) {
        override private[remote] def createRemoteLogMetadataManager(): RemoteLogMetadataManager = rlmmManager
        override private[remote] def createRemoteStorageManager(): ClassLoaderAwareRemoteStorageManager = rsmManager
      }
    remoteLogManager.onLeadershipChange(Set(partition), Set(), Collections.singletonMap(topic, topicIdPartition.topicId()))

    // If base-offset=45 and fetch-size=301, then the entry won't exists in the offset index, returns next
    // remote/local segment base offset.
    val segmentMetadata = new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
      45, 99, -1L, brokerId, -1L, 1024, Collections.singletonMap(0, 45))
    val expectedFetchDataInfo = remoteLogManager.addAbortedTransactions(baseOffset, segmentMetadata, fetchDataInfo)

    assertTrue(expectedFetchDataInfo.abortedTransactions.isDefined)
    assertEquals(abortedTxns.map(_.asAbortedTransaction), expectedFetchDataInfo.abortedTransactions.get)

    verify(log, rlmmManager, rsmManager, records, partition)
  }

  @Test
  def testCollectAbortedTransactionsIteratesNextLocalSegment(): Unit = {
    cache.assign(0, 0)

    val baseOffset = 45
    val timeIdx = new TimeIndex(nonExistentTempFile(), baseOffset, maxIndexSize = 30 * 12)
    val txnIdx = new TransactionIndex(baseOffset, TestUtils.tempFile())
    val offsetIdx = new OffsetIndex(nonExistentTempFile(), baseOffset, maxIndexSize = 4 * 8)
    offsetIdx.append(baseOffset + 0, 0)
    offsetIdx.append(baseOffset + 1, 100)
    offsetIdx.append(baseOffset + 2, 200)
    offsetIdx.append(baseOffset + 3, 300)

    val nextTxnIdx = new TransactionIndex(100L, TestUtils.tempFile())
    val abortedTxns = List(
      new AbortedTxn(producerId = 0L, firstOffset = 50, lastOffset = 105, lastStableOffset = 60),
      new AbortedTxn(producerId = 1L, firstOffset = 55, lastOffset = 120, lastStableOffset = 100)
    )
    abortedTxns.foreach(nextTxnIdx.append)

    val logSegment: LogSegment = createNiceMock(classOf[LogSegment])
    expect(logSegment.txnIndex).andReturn(nextTxnIdx).anyTimes()

    val log: Log = createNiceMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    expect(log.logSegments).andReturn(List(logSegment)).anyTimes()
    expect(log.remoteLogEnabled()).andReturn(true).anyTimes()

    val rlmmManager: RemoteLogMetadataManager = createNiceMock(classOf[RemoteLogMetadataManager])
    expect(rlmmManager.remoteLogSegmentMetadata(EasyMock.eq(topicIdPartition), anyInt(), anyLong()))
      .andReturn(Optional.empty()).anyTimes()
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt()))
      .andReturn(Optional.empty()).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(topicIdPartition)).andReturn(Collections.emptyIterator()).anyTimes()

    val rsmManager: ClassLoaderAwareRemoteStorageManager = createNiceMock(classOf[ClassLoaderAwareRemoteStorageManager])
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.OFFSET))).andReturn(new FileInputStream(offsetIdx.file)).anyTimes()
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.TIMESTAMP))).andReturn(new FileInputStream(timeIdx.file)).anyTimes()
    expect(rsmManager.fetchIndex(anyObject(), EasyMock.eq(IndexType.TRANSACTION))).andReturn(new FileInputStream(txnIdx.file)).anyTimes()

    val records: Records = createNiceMock(classOf[Records])
    expect(records.sizeInBytes()).andReturn(301).anyTimes()
    val fetchDataInfo = FetchDataInfo(LogOffsetMetadata(baseOffset, Log.UnknownOffset, 0), records)

    val topic = topicIdPartition.topicPartition().topic()
    val partition: Partition = createMock(classOf[Partition])
    expect(partition.topic).andReturn(topic).anyTimes()
    expect(partition.topicPartition).andReturn(topicIdPartition.topicPartition()).anyTimes()
    expect(partition.log).andReturn(Option(log)).anyTimes()
    expect(partition.getLeaderEpoch).andReturn(0).anyTimes()

    replay(logSegment, log, rlmmManager, rsmManager, records, partition)
    val remoteLogManager =
      new RemoteLogManager(_ => Option(log), (_, _) => {}, rlmConfig, time, 1, clusterId, logsDir, brokerTopicStats) {
        override private[remote] def createRemoteLogMetadataManager(): RemoteLogMetadataManager = rlmmManager
        override private[remote] def createRemoteStorageManager(): ClassLoaderAwareRemoteStorageManager = rsmManager
      }
    remoteLogManager.onLeadershipChange(Set(partition), Set(), Collections.singletonMap(topic, topicIdPartition.topicId()))

    // If base-offset=45 and fetch-size=301, then the entry won't exists in the offset index, returns next
    // remote/local segment base offset.
    val segmentMetadata = new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
      45, 99, -1L, brokerId, -1L, 1024, Collections.singletonMap(0, 45))
    val expectedFetchDataInfo = remoteLogManager.addAbortedTransactions(baseOffset, segmentMetadata, fetchDataInfo)

    assertTrue(expectedFetchDataInfo.abortedTransactions.isDefined)
    assertEquals(abortedTxns.map(_.asAbortedTransaction), expectedFetchDataInfo.abortedTransactions.get)

    verify(logSegment, log, rlmmManager, rsmManager, records, partition)
  }

  @Test
  def testGetClassLoaderAwareRemoteStorageManager(): Unit = {
    val rsmManager: ClassLoaderAwareRemoteStorageManager = createNiceMock(classOf[ClassLoaderAwareRemoteStorageManager])
    val remoteLogManager =
      new RemoteLogManager(_ => None, (_, _) => {}, rlmConfig, time, 1, clusterId, logsDir, brokerTopicStats) {
        override private[remote] def createRemoteStorageManager(): ClassLoaderAwareRemoteStorageManager = rsmManager
      }
    assertEquals(rsmManager, remoteLogManager.storageManager())
  }

  @ParameterizedTest(name = "testDeleteLogSegmentDueToRetentionTimeBreach segmentCount={0} deletableSegmentCount={1}")
  @CsvSource(value = Array("50, 0", "50, 1", "50, 23", "50, 50"))
  def testDeleteLogSegmentDueToRetentionTimeBreach(segmentCount: Int, deletableSegmentCount: Int): Unit = {
    val recordsPerSegment = 100
    val epochCheckpoints = Seq(0 -> 0, 1 -> 20, 3 -> 50, 4 -> 100)
    epochCheckpoints.foreach { case (epoch, startOffset) => cache.assign(epoch, startOffset) }
    val currentLeaderEpoch = epochCheckpoints.last._1

    val logConfig: LogConfig = createMock(classOf[LogConfig])
    expect(logConfig.retentionMs).andReturn(1).anyTimes()
    expect(logConfig.retentionSize).andReturn(-1).anyTimes()

    val log: Log = createMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    expect(log.config).andReturn(logConfig).anyTimes()
    expect(log.size).andReturn(0).anyTimes()

    var logStartOffset: Option[Long] = None
    val rsmManager: ClassLoaderAwareRemoteStorageManager = createMock(classOf[ClassLoaderAwareRemoteStorageManager])
    val rlmmManager: RemoteLogMetadataManager = createMock(classOf[RemoteLogMetadataManager])
    val remoteLogManager =
      new RemoteLogManager(_ => Option(log), (_, startOffset) => logStartOffset = Option(startOffset), rlmConfig, time,
        brokerId, clusterId, logsDir, brokerTopicStats) {
        override private[remote] def createRemoteStorageManager(): ClassLoaderAwareRemoteStorageManager = rsmManager
        override private[remote] def createRemoteLogMetadataManager() = rlmmManager
      }
    val segmentMetadataList = listRemoteLogSegmentMetadataByTime(segmentCount, deletableSegmentCount, recordsPerSegment)
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt()))
      .andReturn(Optional.empty()).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(topicIdPartition)).andReturn(segmentMetadataList.iterator.asJava).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(EasyMock.eq(topicIdPartition), anyInt())).andAnswer(() => {
      val leaderEpoch = getCurrentArgument[Int](1)
      if (leaderEpoch == 0)
        segmentMetadataList.take(1).iterator.asJava
      else if (leaderEpoch == 4)
        segmentMetadataList.drop(1).iterator.asJava
      else
        Collections.emptyIterator()
    }).anyTimes()
    expect(rlmmManager.updateRemoteLogSegmentMetadata(anyObject(classOf[RemoteLogSegmentMetadataUpdate]))).anyTimes()

    val args1 = newCapture[RemoteLogSegmentMetadata](CaptureType.ALL)
    expect(rsmManager.deleteLogSegmentData(capture(args1))).anyTimes()
    replay(logConfig, log, rlmmManager, rsmManager)

    val rlmTask = new remoteLogManager.RLMTask(topicIdPartition)
    rlmTask.convertToLeader(currentLeaderEpoch)
    rlmTask.handleExpiredRemoteLogSegments()

    assertEquals(deletableSegmentCount, args1.getValues.size())
    if (deletableSegmentCount > 0) {
      val expectedEndMetadata = segmentMetadataList(deletableSegmentCount-1)
      assertEquals(segmentMetadataList.head, args1.getValues.asScala.head)
      assertEquals(expectedEndMetadata, args1.getValues.asScala.reverse.head)
      assertEquals(expectedEndMetadata.endOffset()+1, logStartOffset.get)
    }
    verify(logConfig, log, rlmmManager, rsmManager)
  }

  @ParameterizedTest(name = "testDeleteLogSegmentDueToRetentionSizeBreach segmentCount={0} deletableSegmentCount={1}")
  @CsvSource(value = Array("50, 0", "50, 1", "50, 23", "50, 50"))
  def testDeleteLogSegmentDueToRetentionSizeBreach(segmentCount: Int, deletableSegmentCount: Int): Unit = {
    val recordsPerSegment = 100
    val epochCheckpoints = Seq(0 -> 0, 1 -> 20, 3 -> 50, 4 -> 100)
    epochCheckpoints.foreach { case (epoch, startOffset) => cache.assign(epoch, startOffset) }
    val currentLeaderEpoch = epochCheckpoints.last._1

    val localLogSegmentsSize = 500L
    val retentionSize = (segmentCount - deletableSegmentCount) * 100 + localLogSegmentsSize
    val logConfig: LogConfig = createMock(classOf[LogConfig])
    expect(logConfig.retentionMs).andReturn(-1).anyTimes()
    expect(logConfig.retentionSize).andReturn(retentionSize).anyTimes()

    val log: Log = createMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    expect(log.config).andReturn(logConfig).anyTimes()
    expect(log.size).andReturn(localLogSegmentsSize).anyTimes()

    var logStartOffset: Option[Long] = None
    val rsmManager: ClassLoaderAwareRemoteStorageManager = createMock(classOf[ClassLoaderAwareRemoteStorageManager])
    val rlmmManager: RemoteLogMetadataManager = createMock(classOf[RemoteLogMetadataManager])
    val remoteLogManager =
      new RemoteLogManager(_ => Option(log), (_, startOffset) => logStartOffset = Option(startOffset), rlmConfig, time,
        brokerId, clusterId, logsDir, brokerTopicStats) {
        override private[remote] def createRemoteStorageManager(): ClassLoaderAwareRemoteStorageManager = rsmManager
        override private[remote] def createRemoteLogMetadataManager() = rlmmManager
      }
    val segmentMetadataList = listRemoteLogSegmentMetadata(segmentCount, recordsPerSegment)
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt()))
      .andReturn(Optional.empty()).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(topicIdPartition)).andReturn(segmentMetadataList.iterator.asJava).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(EasyMock.eq(topicIdPartition), anyInt())).andAnswer(() => {
      val leaderEpoch = getCurrentArgument[Int](1)
      if (leaderEpoch == 0)
        segmentMetadataList.take(1).iterator.asJava
      else if (leaderEpoch == 4)
        segmentMetadataList.drop(1).iterator.asJava
      else
        Collections.emptyIterator()
    }).anyTimes()
    expect(rlmmManager.updateRemoteLogSegmentMetadata(anyObject(classOf[RemoteLogSegmentMetadataUpdate]))).anyTimes()

    val args1 = newCapture[RemoteLogSegmentMetadata](CaptureType.ALL)
    expect(rsmManager.deleteLogSegmentData(capture(args1))).anyTimes()
    replay(logConfig, log, rlmmManager, rsmManager)

    val rlmTask = new remoteLogManager.RLMTask(topicIdPartition)
    rlmTask.convertToLeader(currentLeaderEpoch)
    rlmTask.handleExpiredRemoteLogSegments()

    assertEquals(deletableSegmentCount, args1.getValues.size())
    if (deletableSegmentCount > 0) {
      val expectedEndMetadata = segmentMetadataList(deletableSegmentCount-1)
      assertEquals(segmentMetadataList.head, args1.getValues.asScala.head)
      assertEquals(expectedEndMetadata, args1.getValues.asScala.reverse.head)
      assertEquals(expectedEndMetadata.endOffset()+1, logStartOffset.get)
    }
    verify(logConfig, log, rlmmManager, rsmManager)
  }

  @ParameterizedTest(name = "testDeleteLogSegmentDueToRetentionTimeAndSizeBreach segmentCount={0} deletableSegmentCountByTime={1} deletableSegmentCountBySize={2}")
  @CsvSource(value = Array("50, 0, 0", "50, 1, 5", "50, 23, 15", "50, 50, 50"))
  def testDeleteLogSegmentDueToRetentionTimeAndSizeBreach(segmentCount: Int,
                                                          deletableSegmentCountByTime: Int,
                                                          deletableSegmentCountBySize: Int): Unit = {
    val recordsPerSegment = 100
    val epochCheckpoints = Seq(0 -> 0, 1 -> 20, 3 -> 50, 4 -> 100)
    epochCheckpoints.foreach { case (epoch, startOffset) => cache.assign(epoch, startOffset) }
    val currentLeaderEpoch = epochCheckpoints.last._1

    val localLogSegmentsSize = 500L
    val retentionSize = (segmentCount - deletableSegmentCountBySize) * 100 + localLogSegmentsSize
    val logConfig: LogConfig = createMock(classOf[LogConfig])
    expect(logConfig.retentionMs).andReturn(1).anyTimes()
    expect(logConfig.retentionSize).andReturn(retentionSize).anyTimes()

    val log: Log = createMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    expect(log.config).andReturn(logConfig).anyTimes()
    expect(log.size).andReturn(localLogSegmentsSize).anyTimes()

    var logStartOffset: Option[Long] = None
    val rsmManager: ClassLoaderAwareRemoteStorageManager = createMock(classOf[ClassLoaderAwareRemoteStorageManager])
    val rlmmManager: RemoteLogMetadataManager = createMock(classOf[RemoteLogMetadataManager])
    val remoteLogManager =
      new RemoteLogManager(_ => Option(log), (_, startOffset) => logStartOffset = Option(startOffset), rlmConfig, time,
        brokerId, clusterId, logsDir, brokerTopicStats) {
        override private[remote] def createRemoteStorageManager(): ClassLoaderAwareRemoteStorageManager = rsmManager
        override private[remote] def createRemoteLogMetadataManager() = rlmmManager
      }
    val segmentMetadataList = listRemoteLogSegmentMetadataByTime(segmentCount, deletableSegmentCountByTime, recordsPerSegment)
    expect(rlmmManager.highestOffsetForEpoch(EasyMock.eq(topicIdPartition), anyInt()))
      .andReturn(Optional.empty()).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(topicIdPartition)).andReturn(segmentMetadataList.iterator.asJava).anyTimes()
    expect(rlmmManager.listRemoteLogSegments(EasyMock.eq(topicIdPartition), anyInt())).andAnswer(() => {
      val leaderEpoch = getCurrentArgument[Int](1)
      if (leaderEpoch == 0)
        segmentMetadataList.take(1).iterator.asJava
      else if (leaderEpoch == 4)
        segmentMetadataList.drop(1).iterator.asJava
      else
        Collections.emptyIterator()
    }).anyTimes()
    expect(rlmmManager.updateRemoteLogSegmentMetadata(anyObject(classOf[RemoteLogSegmentMetadataUpdate]))).anyTimes()

    val args1 = newCapture[RemoteLogSegmentMetadata](CaptureType.ALL)
    expect(rsmManager.deleteLogSegmentData(capture(args1))).anyTimes()
    replay(logConfig, log, rlmmManager, rsmManager)

    val rlmTask = new remoteLogManager.RLMTask(topicIdPartition)
    rlmTask.convertToLeader(currentLeaderEpoch)
    rlmTask.handleExpiredRemoteLogSegments()

    val deletableSegmentCount = Math.max(deletableSegmentCountBySize, deletableSegmentCountByTime)
    assertEquals(deletableSegmentCount, args1.getValues.size())
    if (deletableSegmentCount > 0) {
      val expectedEndMetadata = segmentMetadataList(deletableSegmentCount-1)
      assertEquals(segmentMetadataList.head, args1.getValues.asScala.head)
      assertEquals(expectedEndMetadata, args1.getValues.asScala.reverse.head)
      assertEquals(expectedEndMetadata.endOffset()+1, logStartOffset.get)
    }
    verify(logConfig, log, rlmmManager, rsmManager)
  }

  @Test
  def testGetLeaderEpochCheckpoint(): Unit = {
    val epochs = Seq(EpochEntry(0, 33), EpochEntry(1, 43), EpochEntry(2, 99), EpochEntry(3, 105))
    epochs.foreach(epochEntry => cache.assign(epochEntry.epoch, epochEntry.startOffset))

    val log: Log = createMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    replay(log)
    val remoteLogManager = new RemoteLogManager(_ => Option(log), (_, _) => {}, rlmConfig, time, brokerId = 1,
      clusterId, logsDir, brokerTopicStats)

    var actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = -1, endOffset = 200).read()
    assertEquals(epochs.take(4), actual)
    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = -1, endOffset = 105).read()
    assertEquals(epochs.take(3), actual)
    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = -1, endOffset = 100).read()
    assertEquals(epochs.take(3), actual)

    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = 34, endOffset = 200).read()
    assertEquals(Seq(EpochEntry(0, 34)) ++ epochs.slice(1, 4), actual)
    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = 43, endOffset = 200).read()
    assertEquals(epochs.slice(1, 4), actual)

    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = 34, endOffset = 100).read()
    assertEquals(Seq(EpochEntry(0, 34)) ++ epochs.slice(1, 3), actual)
    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = 34, endOffset = 30).read()
    assertTrue(actual.isEmpty)
    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = 101, endOffset = 101).read()
    assertTrue(actual.isEmpty)
    actual = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = 101, endOffset = 102).read()
    assertEquals(Seq(EpochEntry(2, 101)), actual)
  }

  @Test
  def testGetLeaderEpochCheckpointEncoding(): Unit = {
    val epochs = Seq(EpochEntry(0, 33), EpochEntry(1, 43), EpochEntry(2, 99), EpochEntry(3, 105))
    epochs.foreach(epochEntry => cache.assign(epochEntry.epoch, epochEntry.startOffset))

    val log: Log = createMock(classOf[Log])
    expect(log.leaderEpochCache).andReturn(Option(cache)).anyTimes()
    replay(log)
    val remoteLogManager = new RemoteLogManager(_ => Option(log), (_, _) => {}, rlmConfig, time, brokerId = 1,
      clusterId, logsDir, brokerTopicStats)

    val tmpFile = TestUtils.tempFile()
    val checkpoint = remoteLogManager.getLeaderEpochCheckpoint(log, startOffset = -1, endOffset = 200)
    assertEquals(epochs, checkpoint.read())

    Files.write(tmpFile.toPath, checkpoint.readAsByteBuffer().array())
    assertEquals(epochs, new LeaderEpochCheckpointFile(tmpFile).read())
  }

  private def listRemoteLogSegmentMetadataByTime(segmentCount: Int,
                                                 deletableSegmentCount: Int,
                                                 recordsPerSegment: Int): List[RemoteLogSegmentMetadata] = {
    val result = mutable.Buffer.empty[RemoteLogSegmentMetadata]
    for (idx <- 0 until segmentCount) {
      val timestamp = if (idx < deletableSegmentCount) time.milliseconds()-1 else time.milliseconds()
      val startOffset = idx * recordsPerSegment
      val endOffset = startOffset + recordsPerSegment - 1
      val segmentLeaderEpochs = truncateAndGetLeaderEpochs(cache, startOffset, endOffset)
      result += new RemoteLogSegmentMetadata(new RemoteLogSegmentId(topicIdPartition, Uuid.randomUuid()),
        startOffset, endOffset, timestamp, brokerId, timestamp, 100, segmentLeaderEpochs)
    }
    result.toList
  }

  private def listRemoteLogSegmentMetadata(segmentCount: Int, recordsPerSegment: Int): List[RemoteLogSegmentMetadata] = {
    listRemoteLogSegmentMetadataByTime(segmentCount, 0, recordsPerSegment)
  }

  private def nonExistentTempFile(): File = {
    val file = TestUtils.tempFile()
    file.delete()
    file
  }

  private def createRLMConfig(props: Properties = new Properties): RemoteLogManagerConfig = {
    props.put(RemoteLogManagerConfig.REMOTE_LOG_STORAGE_SYSTEM_ENABLE_PROP, true.toString)
    props.put(RemoteLogManagerConfig.REMOTE_STORAGE_MANAGER_CLASS_NAME_PROP, "org.apache.kafka.server.log.remote.storage.NoOpRemoteStorageManager")
    props.put(RemoteLogManagerConfig.REMOTE_LOG_METADATA_MANAGER_CLASS_NAME_PROP, "org.apache.kafka.server.log.remote.storage.NoOpRemoteLogMetadataManager")
    val config = new AbstractConfig(RemoteLogManagerConfig.CONFIG_DEF, props)
    new RemoteLogManagerConfig(config)
  }

  private def checkpoint(): LeaderEpochCheckpoint = {
    new LeaderEpochCheckpoint {
      private var epochs: Seq[EpochEntry] = Seq()
      override def write(epochs: Iterable[EpochEntry]): Unit = this.epochs = epochs.toSeq
      override def read(): Seq[EpochEntry] = this.epochs
    }
  }

  private def truncateAndGetLeaderEpochs(cache: LeaderEpochFileCache,
                                         startOffset: Long,
                                         endOffset: Long): util.Map[Integer, lang.Long] = {
    val myCheckpoint = checkpoint()
    val myCache = cache.writeTo(myCheckpoint)
    myCache.truncateFromStart(startOffset)
    myCache.truncateFromEnd(endOffset)
    myCheckpoint.read().map(e => Integer.valueOf(e.epoch) -> lang.Long.valueOf(e.startOffset)).toMap.asJava
  }
}