/**
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
package kafka.controller

import java.net.SocketTimeoutException
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

import com.yammer.metrics.core.{Gauge, Timer}
import kafka.api._
import kafka.cluster.Broker
import kafka.metrics.KafkaMetricsGroup
import kafka.server.KafkaConfig
import kafka.utils._
import kafka.utils.Implicits._
import org.apache.kafka.clients._
import org.apache.kafka.common.message.LeaderAndIsrRequestData.LeaderAndIsrPartitionState
import org.apache.kafka.common.message.StopReplicaRequestData.{StopReplicaPartitionState, StopReplicaTopicState}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.network._
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.message.UpdateMetadataRequestData.{UpdateMetadataBroker, UpdateMetadataEndpoint, UpdateMetadataPartitionState}
import org.apache.kafka.common.requests._
import org.apache.kafka.common.security.JaasContext
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.common.utils.{LogContext, Time}
import org.apache.kafka.common.{KafkaException, Node, Reconfigurable, TopicPartition, Uuid}

import scala.jdk.CollectionConverters._
import scala.collection.mutable.HashMap
import scala.collection.{Seq, Map, Set, mutable}

object ControllerChannelManager {
  val QueueSizeMetricName = "QueueSize"
  val RequestRateAndQueueTimeMetricName = "RequestRateAndQueueTimeMs"
}

class ControllerChannelManager(controllerContext: ControllerContext,
                               config: KafkaConfig,
                               time: Time,
                               metrics: Metrics,
                               stateChangeLogger: StateChangeLogger,
                               threadNamePrefix: Option[String] = None) extends Logging with KafkaMetricsGroup {
  import ControllerChannelManager._

  protected val brokerStateInfo = new HashMap[Int, ControllerBrokerStateInfo]
  private val brokerLock = new Object
  val brokerResponseSensors: mutable.Map[ApiKeys, BrokerResponseTimeStats] = mutable.HashMap.empty
  this.logIdent = "[Channel manager on controller " + config.brokerId + "]: "

  newGauge("TotalQueueSize",
    () => brokerLock synchronized {
      brokerStateInfo.values.iterator.map(_.messageQueue.size).sum
    }
  )

  def startup() = {
    controllerContext.liveOrShuttingDownBrokers.foreach(addNewBroker)

    brokerLock synchronized {
      brokerStateInfo.foreach(brokerState => startRequestSendThread(brokerState._1))
    }
    initBrokerResponseSensors()
  }

  def shutdown() = {
    brokerLock synchronized {
      brokerStateInfo.values.toList.foreach(removeExistingBroker)
    }
    removeBrokerResponseSensors()
  }

  def initBrokerResponseSensors(): Unit = {
    Array(ApiKeys.STOP_REPLICA, ApiKeys.LEADER_AND_ISR, ApiKeys.UPDATE_METADATA, ApiKeys.LI_COMBINED_CONTROL).foreach { k: ApiKeys =>
      brokerResponseSensors.put(k, new BrokerResponseTimeStats(k))
    }
  }

  def removeBrokerResponseSensors(): Unit = {
    brokerResponseSensors.keySet.foreach { k: ApiKeys =>
      brokerResponseSensors(k).removeMetrics()
      brokerResponseSensors.remove(k)
    }
  }

  def sendRequest(brokerId: Int, request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                  callback: AbstractResponse => Unit = null): Unit = {
    brokerLock synchronized {
      val stateInfoOpt = brokerStateInfo.get(brokerId)
      stateInfoOpt match {
        case Some(stateInfo) =>
          stateInfo.messageQueue.put(QueueItem(request.apiKey, request, callback, time.milliseconds()))
        case None =>
          warn(s"Not sending request $request to broker $brokerId, since it is offline.")
      }
    }
  }

  def addBroker(broker: Broker): Unit = {
    // be careful here. Maybe the startup() API has already started the request send thread
    brokerLock synchronized {
      if (!brokerStateInfo.contains(broker.id)) {
        addNewBroker(broker)
        startRequestSendThread(broker.id)
      }
    }
  }

  def removeBroker(brokerId: Int): Unit = {
    brokerLock synchronized {
      removeExistingBroker(brokerStateInfo(brokerId))
    }
  }

  private def addNewBroker(broker: Broker): Unit = {
    val messageQueue = new LinkedBlockingQueue[QueueItem]
    debug(s"Controller ${config.brokerId} trying to connect to broker ${broker.id}")
    val controllerToBrokerListenerName = config.controlPlaneListenerName.getOrElse(config.interBrokerListenerName)
    val controllerToBrokerSecurityProtocol = config.controlPlaneSecurityProtocol.getOrElse(config.interBrokerSecurityProtocol)
    val brokerNode = broker.node(controllerToBrokerListenerName)
    val logContext = new LogContext(s"[Controller id=${config.brokerId}, targetBrokerId=${brokerNode.idString}] ")
    val (networkClient, reconfigurableChannelBuilder) = {
      val channelBuilder = ChannelBuilders.clientChannelBuilder(
        controllerToBrokerSecurityProtocol,
        JaasContext.Type.SERVER,
        config,
        controllerToBrokerListenerName,
        config.saslMechanismInterBrokerProtocol,
        time,
        config.saslInterBrokerHandshakeRequestEnable,
        logContext
      )
      val reconfigurableChannelBuilder = channelBuilder match {
        case reconfigurable: Reconfigurable =>
          config.addReconfigurable(reconfigurable)
          Some(reconfigurable)
        case _ => None
      }
      val selector = new Selector(
        NetworkReceive.UNLIMITED,
        Selector.NO_IDLE_TIMEOUT_MS,
        metrics,
        time,
        "controller-channel",
        Map("broker-id" -> brokerNode.idString).asJava,
        false,
        channelBuilder,
        logContext
      )
      val networkClient = new NetworkClient(
        selector,
        new ManualMetadataUpdater(Seq(brokerNode).asJava),
        config.brokerId.toString,
        1,
        0,
        0,
        Selectable.USE_DEFAULT_BUFFER_SIZE,
        Selectable.USE_DEFAULT_BUFFER_SIZE,
        config.requestTimeoutMs,
        config.connectionSetupTimeoutMs,
        config.connectionSetupTimeoutMaxMs,
        time,
        false,
        new ApiVersions,
        logContext
      )
      (networkClient, reconfigurableChannelBuilder)
    }
    val threadName = threadNamePrefix match {
      case None => s"Controller-${config.brokerId}-to-broker-${broker.id}-send-thread"
      case Some(name) => s"$name:Controller-${config.brokerId}-to-broker-${broker.id}-send-thread"
    }

    val requestRateAndQueueTimeMetrics = newTimer(
      RequestRateAndQueueTimeMetricName, TimeUnit.MILLISECONDS, TimeUnit.SECONDS, brokerMetricTags(broker.id)
    )

    val requestThread = new RequestSendThread(config.brokerId, controllerContext, messageQueue, networkClient,
      brokerNode, config, time, requestRateAndQueueTimeMetrics, stateChangeLogger, threadName, this)
    requestThread.setDaemon(false)

    val queueSizeGauge = newGauge(QueueSizeMetricName, () => messageQueue.size, brokerMetricTags(broker.id))

    brokerStateInfo.put(broker.id, ControllerBrokerStateInfo(networkClient, brokerNode, messageQueue,
      requestThread, queueSizeGauge, requestRateAndQueueTimeMetrics, reconfigurableChannelBuilder))
  }

  private def brokerMetricTags(brokerId: Int) = Map("broker-id" -> brokerId.toString)

  private def removeExistingBroker(brokerState: ControllerBrokerStateInfo): Unit = {
    try {
      // Shutdown the RequestSendThread before closing the NetworkClient to avoid the concurrent use of the
      // non-threadsafe classes as described in KAFKA-4959.
      // The call to shutdownLatch.await() in ShutdownableThread.shutdown() serves as a synchronization barrier that
      // hands off the NetworkClient from the RequestSendThread to the ZkEventThread.
      brokerState.reconfigurableChannelBuilder.foreach(config.removeReconfigurable)
      brokerState.requestSendThread.shutdown()
      brokerState.networkClient.close()
      brokerState.messageQueue.clear()
      removeMetric(QueueSizeMetricName, brokerMetricTags(brokerState.brokerNode.id))
      removeMetric(RequestRateAndQueueTimeMetricName, brokerMetricTags(brokerState.brokerNode.id))
      brokerStateInfo.remove(brokerState.brokerNode.id)
    } catch {
      case e: Throwable => error("Error while removing broker by the controller", e)
    }
  }

  protected def startRequestSendThread(brokerId: Int): Unit = {
    val requestThread = brokerStateInfo(brokerId).requestSendThread
    if (requestThread.getState == Thread.State.NEW)
      requestThread.start()
  }
}

case class QueueItem(apiKey: ApiKeys, request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                     callback: AbstractResponse => Unit, enqueueTimeMs: Long)

case class LatestRequestStatus(isInFlight: Boolean, isInQueue: Boolean, enqueueTimeMs: Long)

class RequestSendThread(val controllerId: Int,
                        val controllerContext: ControllerContext,
                        val queue: BlockingQueue[QueueItem],
                        val networkClient: NetworkClient,
                        val brokerNode: Node,
                        val config: KafkaConfig,
                        val time: Time,
                        val requestRateAndQueueTimeMetrics: Timer,
                        val stateChangeLogger: StateChangeLogger,
                        name: String,
                        val controllerChannelManager: ControllerChannelManager)
  extends ShutdownableThread(name = name) with KafkaMetricsGroup {

  logIdent = s"[RequestSendThread controllerId=$controllerId brokerId=${brokerNode.id()}] "

  private val MaxRequestAgeMetricName = "maxRequestAge"

  private val socketTimeoutMs = config.controllerSocketTimeoutMs

  private val controllerRequestMerger = new ControllerRequestMerger(config)

  private var firstUpdateMetadataWithPartitionsSent = false

  private var firstFullLeaderAndIsrSent = false

  @volatile private var latestRequestStatus = LatestRequestStatus(isInFlight = false, isInQueue = false, 0)

  // This metric reports the queued time of the latest request from the queue
  // Case 1: if there is an inflight request, the metric need to report the age of the inflight request.
  // Case 2: if there is no inflight request and there are requests inside the queue, the metric need to report
  //         the age of the oldest item in the queue, which is the one that should be dequeued next.
  // Case 3: if there is no inflight request and there are no requests inside the queue, the metric should report 0.
  val queueTimeGauge = newGauge(
    MaxRequestAgeMetricName,
    new Gauge[Long] {
      def value: Long =
        if (latestRequestStatus.isInFlight || latestRequestStatus.isInQueue) time.milliseconds() - latestRequestStatus.enqueueTimeMs
        else 0
    },
    brokerMetricTags(brokerNode.id())
  )

  private def brokerMetricTags(brokerId: Int) = Map("broker-id" -> brokerId.toString)

  def backoff(): Unit = pause(100, TimeUnit.MILLISECONDS)

  override def doWork(): Unit = {
    val (requestBuilder, callback) = nextRequestAndCallback()
    sendAndReceive(requestBuilder, callback)
  }

  private def nextRequestAndCallback(): (AbstractControlRequest.Builder[_ <: AbstractControlRequest], AbstractResponse => Unit) = {
    if (controllerRequestMerger.hasPendingRequests() ||
      (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1 &&
        config.liCombinedControlRequestEnable &&
        firstUpdateMetadataWithPartitionsSent &&
        // The LeaderAndIsrRequest starts having the full/incremental flag since IBP KAFKA_2_8_IV1.
        // Thus, we require the firstFullLeaderAndIsrSent to be set only when IBP >= KAFKA_2_8_IV1
        (config.interBrokerProtocolVersion < KAFKA_2_8_IV1 || firstFullLeaderAndIsrSent ||
          // Brokers that don't host any replicas will never receive a LeaderAndIsr request, including
          // the preferred controllers and maintenance brokers. We treat them specially here
          // so that they can have the LiCombinedControl requests enabled.
          controllerContext.partitionsOnBroker(brokerNode.id()).isEmpty))) {
      // Only start the merging logic after the first UpdateMetadata request with partitions,
      // since the first UpdateMetadata request with partitions may contain hundreds of thousands of partitions,
      // and thus needs to be cached and shared by all brokers in order to prevent OOM

      // there are 4 cases regarding the state of the queue and the controllerRequestMerger
      // case 1: queue not empty, merger not empty {action: merge and send first merged request}
      // case 2: queue not empty, merger empty {action: merge and send first merged request}
      // case 3: queue empty, merger not empty {action: send latest merged request}
      // case 4: queue empty, merger empty {action: block and wait until queue becomes non-empty, then transition to case 1 or 3}

      // handle case 4 first
      if (!controllerRequestMerger.hasPendingRequests()) {
        val QueueItem(apiKey, requestBuilder, callback, enqueueTimeMs) = queue.take()
        latestRequestStatus = LatestRequestStatus(isInFlight = true, isInQueue = false, enqueueTimeMs)
        mergeControlRequest(enqueueTimeMs, apiKey, requestBuilder, callback)
      }

      // now we are guaranteed that the controllerRequestMerger is not empty (case 1 or 3)
      // drain the queue until the queue is empty
      // one concurrent access case considering the producer of the queue:
      // an item is put to the queue right after the condition check below.
      // That behavior does not change correctness since the inserted item will be picked up in the next round
      while (!queue.isEmpty) {
        val QueueItem(apiKey, requestBuilder, callback, enqueueTimeMs) = queue.take()
        mergeControlRequest(enqueueTimeMs, apiKey, requestBuilder, callback)
      }

      val requestBuilder = controllerRequestMerger.pollLatestRequest()
      (requestBuilder, controllerRequestMerger.triggerCallback _)
    } else {
      // use the old behavior of sending each item in the queue as a separate request
      val QueueItem(apiKey, requestBuilder, callback, enqueueTimeMs) = queue.take()
      latestRequestStatus = LatestRequestStatus(isInFlight = true, isInQueue = false, enqueueTimeMs)
      updateMetrics(apiKey, enqueueTimeMs)
      (requestBuilder, callback)
    }
  }

  private def sendAndReceive(requestBuilder: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
    callback: AbstractResponse => Unit): Unit = {
    var remoteTimeMs: Long = 0

    var clientResponse: ClientResponse = null
    try {
      var isSendSuccessful = false
      while (isRunning && !isSendSuccessful) {
        // if a broker goes down for a long time, then at some point the controller's zookeeper listener will trigger a
        // removeBroker which will invoke shutdown() on this thread. At that point, we will stop retrying.
        try {
          if (!brokerReady()) {
            isSendSuccessful = false
            backoff()
          }
          else {
            val clientRequest = networkClient.newClientRequest(brokerNode.idString, requestBuilder,
              time.milliseconds(), true)
            val remoteTimeStartMs = time.milliseconds()
            stateChangeLogger.withControllerEpoch(controllerContext.epoch).trace(s"sending request to broker $brokerNode: $requestBuilder")

            clientResponse = NetworkClientUtils.sendAndReceive(networkClient, clientRequest, time)
            isSendSuccessful = true
            remoteTimeMs = time.milliseconds() - remoteTimeStartMs

            val nextRequest = queue.peek()
            if (nextRequest != null) {
              latestRequestStatus = LatestRequestStatus(isInFlight = false, isInQueue = true, nextRequest.enqueueTimeMs)
            } else {
              latestRequestStatus = LatestRequestStatus(isInFlight = false, isInQueue = false, 0)
            }
          }
        } catch {
          case e: Throwable => // if the send was not successful, reconnect to broker and resend the message
            warn(s"Controller $controllerId epoch ${controllerContext.epoch} fails to send request $requestBuilder " +
              s"to broker $brokerNode. Reconnecting to broker.", e)
            networkClient.close(brokerNode.idString)
            isSendSuccessful = false
            backoff()
        }
      }
      if (clientResponse != null) {
        val requestHeader = clientResponse.requestHeader
        val api = requestHeader.apiKey
        if (api != ApiKeys.LEADER_AND_ISR && api != ApiKeys.STOP_REPLICA && api != ApiKeys.UPDATE_METADATA &&
          api != ApiKeys.LI_COMBINED_CONTROL)
          throw new KafkaException(s"Unexpected apiKey received: $api")


        if (api == ApiKeys.UPDATE_METADATA && !requestBuilder.asInstanceOf[UpdateMetadataRequest.Builder].partitionStates().isEmpty) {
          firstUpdateMetadataWithPartitionsSent = true
        }
        if (api == ApiKeys.LEADER_AND_ISR &&
          requestBuilder.asInstanceOf[LeaderAndIsrRequest.Builder].`type`() == LeaderAndIsrRequestType.FULL) {
          firstFullLeaderAndIsrSent = true
        }

        val response = clientResponse.responseBody

        stateChangeLogger.withControllerEpoch(controllerContext.epoch).trace(s"Received response " +
          s"$response for request $api with correlation id " +
          s"${requestHeader.correlationId} sent to broker $brokerNode")

        if (callback != null) {
          callback(response)
        }
        controllerChannelManager.brokerResponseSensors(api).updateRemoteTime(remoteTimeMs)
      }
    } catch {
      case e: Throwable =>
        error(s"Controller $controllerId fails to send a request to broker $brokerNode", e)
        // If there is any socket error (eg, socket timeout), the connection is no longer usable and needs to be recreated.
        networkClient.close(brokerNode.idString)
    }
  }

  /**
   * merge a control request
   * @param enqueueTimeMs
   * @param apiKey
   * @param requestBuilder
   * @param callback
   */
  def mergeControlRequest(enqueueTimeMs: Long, apiKey: ApiKeys, requestBuilder: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
    callback: AbstractResponse => Unit): Unit = {
    updateMetrics(apiKey, enqueueTimeMs)
    controllerRequestMerger.addRequest(requestBuilder, callback)
  }

  private def updateMetrics(apiKey: ApiKeys, enqueueTimeMs: Long) = {
    val queueTimeMs = time.milliseconds() - enqueueTimeMs
    requestRateAndQueueTimeMetrics.update(queueTimeMs, TimeUnit.MILLISECONDS)
    controllerChannelManager.brokerResponseSensors(apiKey).updateQueueTime(queueTimeMs)
  }

  private def brokerReady(): Boolean = {
    try {
      if (!NetworkClientUtils.isReady(networkClient, brokerNode, time.milliseconds())) {
        if (!NetworkClientUtils.awaitReady(networkClient, brokerNode, time, socketTimeoutMs))
          throw new SocketTimeoutException(s"Failed to connect within $socketTimeoutMs ms")

        info(s"Controller $controllerId connected to $brokerNode for sending state change requests")
      }

      true
    } catch {
      case e: Throwable =>
        warn(s"Controller $controllerId's connection to broker $brokerNode was unsuccessful", e)
        networkClient.close(brokerNode.idString)
        false
    }
  }

  override def initiateShutdown(): Boolean = {
    if (super.initiateShutdown()) {
      networkClient.initiateClose()
      removeMetric(MaxRequestAgeMetricName, brokerMetricTags(brokerNode.id()))
      true
    } else
      false
  }
}

class ControllerBrokerRequestBatch(config: KafkaConfig,
                                   controllerChannelManager: ControllerChannelManager,
                                   controllerEventManager: ControllerEventManager,
                                   controllerContext: ControllerContext,
                                   stateChangeLogger: StateChangeLogger)
  extends AbstractControllerBrokerRequestBatch(config, controllerContext, stateChangeLogger) {

  def sendEvent(event: ControllerEvent): Unit = {
    controllerEventManager.put(event)
  }

  def sendRequest(brokerId: Int,
                  request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                  callback: AbstractResponse => Unit = null): Unit = {
    controllerChannelManager.sendRequest(brokerId, request, callback)
  }

}

abstract class AbstractControllerBrokerRequestBatch(config: KafkaConfig,
                                                    controllerContext: ControllerContext,
                                                    stateChangeLogger: StateChangeLogger) extends Logging {
  val controllerId: Int = config.brokerId
  val leaderAndIsrRequestMap = mutable.Map.empty[Int, mutable.Map[TopicPartition, LeaderAndIsrPartitionState]]
  val stopReplicaRequestMap = mutable.Map.empty[Int, mutable.Map[TopicPartition, StopReplicaPartitionState]]
  val updateMetadataRequestBrokerSet = mutable.Set.empty[Int]
  val updateMetadataRequestPartitionInfoMap = mutable.Map.empty[TopicPartition, UpdateMetadataPartitionState]

  def sendEvent(event: ControllerEvent): Unit

  def sendRequest(brokerId: Int,
                  request: AbstractControlRequest.Builder[_ <: AbstractControlRequest],
                  callback: AbstractResponse => Unit = null): Unit

  def newBatch(): Unit = {
    // raise error if the previous batch is not empty
    if (leaderAndIsrRequestMap.nonEmpty)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating " +
        s"a new one. Some LeaderAndIsr state changes $leaderAndIsrRequestMap might be lost ")
    if (stopReplicaRequestMap.nonEmpty)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        s"new one. Some StopReplica state changes $stopReplicaRequestMap might be lost ")
    if (updateMetadataRequestBrokerSet.nonEmpty)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        s"new one. Some UpdateMetadata state changes to brokers $updateMetadataRequestBrokerSet with partition info " +
        s"$updateMetadataRequestPartitionInfoMap might be lost ")
  }

  def clear(): Unit = {
    leaderAndIsrRequestMap.clear()
    stopReplicaRequestMap.clear()
    updateMetadataRequestBrokerSet.clear()
    updateMetadataRequestPartitionInfoMap.clear()
  }

  /** 5-arg version of subsequent 6-arg method in order to support tests using (Java) EasyMock, which
   *  can't handle "might have 5 OR 6 args" uncertainty of Scala methods with defaultable args. */
  def addLeaderAndIsrRequestForBrokers(brokerIds: Seq[Int],
                                       topicPartition: TopicPartition,
                                       leaderIsrAndControllerEpoch: LeaderIsrAndControllerEpoch,
                                       replicaAssignment: ReplicaAssignment,
                                       isNew: Boolean): Unit = {
    addLeaderAndIsrRequestForBrokers(brokerIds, topicPartition, leaderIsrAndControllerEpoch, replicaAssignment, isNew, ControllerContextSnapshot(controllerContext))
  }

  def addLeaderAndIsrRequestForBrokers(brokerIds: Seq[Int],
                                       topicPartition: TopicPartition,
                                       leaderIsrAndControllerEpoch: LeaderIsrAndControllerEpoch,
                                       replicaAssignment: ReplicaAssignment,
                                       isNew: Boolean,
                                       controllerContextSnapshot: ControllerContextSnapshot): Unit = {

    brokerIds.filter(_ >= 0).foreach { brokerId =>
      val result = leaderAndIsrRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty)
      val alreadyNew = result.get(topicPartition).exists(_.isNew)
      val leaderAndIsr = leaderIsrAndControllerEpoch.leaderAndIsr
      result.put(topicPartition, new LeaderAndIsrPartitionState()
        .setTopicName(topicPartition.topic)
        .setPartitionIndex(topicPartition.partition)
        .setControllerEpoch(leaderIsrAndControllerEpoch.controllerEpoch)
        .setLeader(leaderAndIsr.leader)
        .setLeaderEpoch(leaderAndIsr.leaderEpoch)
        .setIsr(leaderAndIsr.isr.map(Integer.valueOf).asJava)
        .setZkVersion(leaderAndIsr.zkVersion)
        .setReplicas(replicaAssignment.replicas.map(Integer.valueOf).asJava)
        .setAddingReplicas(replicaAssignment.addingReplicas.map(Integer.valueOf).asJava)
        .setRemovingReplicas(replicaAssignment.removingReplicas.map(Integer.valueOf).asJava)
        .setIsNew(isNew || alreadyNew))
    }

    addUpdateMetadataRequestForBrokers(controllerContextSnapshot.liveOrShuttingDownBrokerIds.toSeq, Set(topicPartition),
      controllerContextSnapshot)
  }

  def addStopReplicaRequestForBrokers(brokerIds: Seq[Int],
                                      topicPartition: TopicPartition,
                                      deletePartition: Boolean): Unit = {
    // A sentinel (-2) is used as an epoch if the topic is queued for deletion. It overrides
    // any existing epoch.
    val leaderEpoch = if (controllerContext.isTopicQueuedUpForDeletion(topicPartition.topic)) {
      LeaderAndIsr.EpochDuringDelete
    } else {
      controllerContext.partitionLeadershipInfo(topicPartition)
        .map(_.leaderAndIsr.leaderEpoch)
        .getOrElse(LeaderAndIsr.NoEpoch)
    }

    brokerIds.filter(_ >= 0).foreach { brokerId =>
      val result = stopReplicaRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty)
      val alreadyDelete = result.get(topicPartition).exists(_.deletePartition)
      result.put(topicPartition, new StopReplicaPartitionState()
          .setPartitionIndex(topicPartition.partition())
          .setLeaderEpoch(leaderEpoch)
          .setDeletePartition(alreadyDelete || deletePartition))
    }
  }

  /** Two-arg version of subsequent 3-arg method in order to support tests using (Java) EasyMock, which
   *  can't handle "might have two OR three args" uncertainty of Scala methods with defaultable args. */
  def addUpdateMetadataRequestForBrokers(brokerIds: Seq[Int],
                                         partitions: collection.Set[TopicPartition]): Unit = {
    addUpdateMetadataRequestForBrokers(brokerIds, partitions, ControllerContextSnapshot(controllerContext))
  }

  /** Send UpdateMetadataRequest to the given brokers for the given partitions and partitions that are being deleted */
  def addUpdateMetadataRequestForBrokers(brokerIds: Seq[Int],
                                         partitions: collection.Set[TopicPartition],
                                         controllerContextSnapshot: ControllerContextSnapshot /* = ControllerContextSnapshot(controllerContext) */): Unit = {
    def updateMetadataRequestPartitionInfo(partition: TopicPartition, beingDeleted: Boolean): Unit = {
      controllerContext.partitionLeadershipInfo(partition) match {  // [PERF:  partitionLeadershipInfo's contrib is tiny: 0.357% + 2*0.268% = 0.893%]
        case Some(LeaderIsrAndControllerEpoch(leaderAndIsr, controllerEpoch)) =>
          val replicas = controllerContext.partitionReplicaAssignment(partition)  // [PERF:  partitionReplicaAssignment's contrib is even tinier:  0.268%]
          val offlineReplicas = replicas.filter(!controllerContextSnapshot.isReplicaOnline(_, partition))
          val updatedLeaderAndIsr =
            if (beingDeleted) LeaderAndIsr.duringDelete(leaderAndIsr.isr)
            else leaderAndIsr

          val partitionStateInfo = new UpdateMetadataPartitionState()
            .setTopicName(partition.topic)
            .setPartitionIndex(partition.partition)
            .setControllerEpoch(controllerEpoch)
            .setLeader(updatedLeaderAndIsr.leader)
            .setLeaderEpoch(updatedLeaderAndIsr.leaderEpoch)
            .setIsr(updatedLeaderAndIsr.isr.map(Integer.valueOf).asJava)
            .setZkVersion(updatedLeaderAndIsr.zkVersion)
            .setReplicas(replicas.map(Integer.valueOf).asJava)
            .setOfflineReplicas(offlineReplicas.map(Integer.valueOf).asJava)
          updateMetadataRequestPartitionInfoMap.put(partition, partitionStateInfo)

        case None =>
          info(s"Leader not yet assigned for partition $partition. Skip sending UpdateMetadataRequest.")
      }
    }

    updateMetadataRequestBrokerSet ++= brokerIds.filter(_ >= 0)
    partitions.foreach(partition => updateMetadataRequestPartitionInfo(partition,
      beingDeleted = controllerContext.topicsToBeDeleted.contains(partition.topic)))  // [PERF:  topicsToBeDeleted's contrib is nonexistent in orig profile (makes sense: not deleting any topics), but should profile Venice cluster's controller init: TODO]
  }

  private def sendLeaderAndIsrRequest(controllerEpoch: Int, stateChangeLog: StateChangeLogger): Unit = {
    val leaderAndIsrRequestVersion: Short =
      if (config.interBrokerProtocolVersion >= KAFKA_2_8_IV1) 6
      else if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1) 5
      else if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV0) 4
      else if (config.interBrokerProtocolVersion >= KAFKA_2_3_IV2) 3
      else if (config.interBrokerProtocolVersion >= KAFKA_2_2_IV0) 2
      else if (config.interBrokerProtocolVersion >= KAFKA_1_0_IV0) 1
      else 0

    val maxBrokerEpoch = controllerContext.maxBrokerEpoch
    leaderAndIsrRequestMap.forKeyValue { (broker, leaderAndIsrPartitionStates) =>
      if (controllerContext.liveOrShuttingDownBrokerIds.contains(broker)) {
        val leaderIds = mutable.Set.empty[Int]
        var numBecomeLeaders = 0
        leaderAndIsrPartitionStates.forKeyValue { (topicPartition, state) =>
          leaderIds += state.leader
          val typeOfRequest = if (broker == state.leader) {
            numBecomeLeaders += 1
            "become-leader"
          } else {
            "become-follower"
          }
          if (stateChangeLog.isTraceEnabled)
            stateChangeLog.trace(s"Sending $typeOfRequest LeaderAndIsr request $state to broker $broker for partition $topicPartition")
        }
        stateChangeLog.info(s"Sending LeaderAndIsr request to broker $broker with $numBecomeLeaders become-leader " +
          s"and ${leaderAndIsrPartitionStates.size - numBecomeLeaders} become-follower partitions")
        val leaders = controllerContext.liveOrShuttingDownBrokers.filter(b => leaderIds.contains(b.id)).map {
          _.node(config.interBrokerListenerName)
        }
        val brokerEpoch = controllerContext.liveBrokerIdAndEpochs(broker)
        val topicIds = leaderAndIsrPartitionStates.keys
          .map(_.topic)
          .toSet[String]
          .map(topic => (topic, controllerContext.topicIds.getOrElse(topic, Uuid.ZERO_UUID)))
          .toMap
        val leaderAndIsrRequestBuilder = if (leaderAndIsrRequestVersion >= 6) {
          // LeaderAndIsr.Type is supported since version 6
          new LeaderAndIsrRequest.Builder(leaderAndIsrRequestVersion, controllerId,
          controllerEpoch, brokerEpoch, maxBrokerEpoch, leaderAndIsrPartitionStates.values.toBuffer.asJava, topicIds.asJava, leaders.asJava,
          controllerContext.shouldSendFullLeaderAndIsr(broker))
        } else {
          new LeaderAndIsrRequest.Builder(leaderAndIsrRequestVersion, controllerId,
            controllerEpoch, brokerEpoch, maxBrokerEpoch, leaderAndIsrPartitionStates.values.toBuffer.asJava, topicIds.asJava, leaders.asJava)
        }


        sendRequest(broker, leaderAndIsrRequestBuilder, (r: AbstractResponse) => {
          val leaderAndIsrResponse = r.asInstanceOf[LeaderAndIsrResponse]
          sendEvent(LeaderAndIsrResponseReceived(leaderAndIsrResponse, broker))
        })
        controllerContext.markLeaderAndIsrSent(broker)
      }
    }
    leaderAndIsrRequestMap.clear()
  }

  private def sendUpdateMetadataRequests(controllerEpoch: Int, stateChangeLog: StateChangeLogger): Unit = {
    stateChangeLog.info(s"Sending UpdateMetadata request to brokers $updateMetadataRequestBrokerSet " +
      s"for ${updateMetadataRequestPartitionInfoMap.size} partitions")

    val partitionStates = updateMetadataRequestPartitionInfoMap.values.toBuffer
    val updateMetadataRequestVersion: Short =
      if (config.interBrokerProtocolVersion >= KAFKA_2_8_IV1) 8
      else if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1) 7
      else if (config.interBrokerProtocolVersion >= KAFKA_2_3_IV2) 6
      else if (config.interBrokerProtocolVersion >= KAFKA_2_2_IV0) 5
      else if (config.interBrokerProtocolVersion >= KAFKA_1_0_IV0) 4
      else if (config.interBrokerProtocolVersion >= KAFKA_0_10_2_IV0) 3
      else if (config.interBrokerProtocolVersion >= KAFKA_0_10_0_IV1) 2
      else if (config.interBrokerProtocolVersion >= KAFKA_0_9_0) 1
      else 0

    val liveBrokers = controllerContext.liveOrShuttingDownBrokers.iterator.map { broker =>
      val endpoints = if (updateMetadataRequestVersion == 0) {
        // Version 0 of UpdateMetadataRequest only supports PLAINTEXT
        val securityProtocol = SecurityProtocol.PLAINTEXT
        val listenerName = ListenerName.forSecurityProtocol(securityProtocol)
        val node = broker.node(listenerName)
        Seq(new UpdateMetadataEndpoint()
          .setHost(node.host)
          .setPort(node.port)
          .setSecurityProtocol(securityProtocol.id)
          .setListener(listenerName.value))
      } else {
        broker.endPoints.map { endpoint =>
          new UpdateMetadataEndpoint()
            .setHost(endpoint.host)
            .setPort(endpoint.port)
            .setSecurityProtocol(endpoint.securityProtocol.id)
            .setListener(endpoint.listenerName.value)
        }
      }
      new UpdateMetadataBroker()
        .setId(broker.id)
        .setEndpoints(endpoints.asJava)
        .setRack(broker.rack.orNull)
    }.toBuffer

    val topicIds = partitionStates.map(_.topicName())
      .distinct
      .filter(controllerContext.topicIds.contains)
      .map(topic => (topic, controllerContext.topicIds(topic))).toMap

    var commonUpdateMetadataRequestBuilder: UpdateMetadataRequest.Builder = null

    def getUpdateMetadataRequestBuilder(brokerEpoch: Long): UpdateMetadataRequest.Builder = {
      if (updateMetadataRequestVersion >= 6) {
        if (commonUpdateMetadataRequestBuilder == null) {
          val maxBrokerEpoch = controllerContext.maxBrokerEpoch
          commonUpdateMetadataRequestBuilder =  new UpdateMetadataRequest.Builder(updateMetadataRequestVersion,
            controllerId, controllerEpoch, AbstractControlRequest.UNKNOWN_BROKER_EPOCH, maxBrokerEpoch, partitionStates.asJava, liveBrokers.asJava, topicIds.asJava)
        }
        commonUpdateMetadataRequestBuilder
      } else {
        new UpdateMetadataRequest.Builder(updateMetadataRequestVersion,
          controllerId, controllerEpoch, brokerEpoch, AbstractControlRequest.UNKNOWN_BROKER_EPOCH, partitionStates.asJava, liveBrokers.asJava, topicIds.asJava)
      }
    }

    updateMetadataRequestBrokerSet.intersect(controllerContext.liveOrShuttingDownBrokerIds).foreach { broker =>
      val brokerEpoch = controllerContext.liveBrokerIdAndEpochs(broker)
      val updateMetadataRequestBuilder = getUpdateMetadataRequestBuilder(brokerEpoch)
      sendRequest(broker, updateMetadataRequestBuilder, (r: AbstractResponse) => {
        val updateMetadataResponse = r.asInstanceOf[UpdateMetadataResponse]
        sendEvent(UpdateMetadataResponseReceived(updateMetadataResponse, broker))
      })

    }
    updateMetadataRequestBrokerSet.clear()
    updateMetadataRequestPartitionInfoMap.clear()
  }

  private def sendStopReplicaRequests(controllerEpoch: Int, stateChangeLog: StateChangeLogger): Unit = {
    val traceEnabled = stateChangeLog.isTraceEnabled
    val stopReplicaRequestVersion: Short =
      if (config.interBrokerProtocolVersion >= KAFKA_2_6_IV0) 4
      else if (config.interBrokerProtocolVersion >= KAFKA_2_4_IV1) 3
      else if (config.interBrokerProtocolVersion >= KAFKA_2_3_IV2) 2
      else if (config.interBrokerProtocolVersion >= KAFKA_2_2_IV0) 1
      else 0

    /**
     * For StopReplica request version < 4, we rely on the isPartitionDeleted function to check a partition's delete flag,
     * this may not be always correct considering a later request's callback may overwrite an earlier
     * request's callback inside the {@link ControllerRequestMerger#addStopReplicaRequest) method.
     * For StopReplica request version 4 and above, we rely on the delete field inside the StopReplica response to
     * check a partition's delete flag, which should be always correct.
     */
    def responseCallback(brokerId: Int, isPartitionDeleted: TopicPartition => Boolean)
                        (response: AbstractResponse): Unit = {
      val stopReplicaResponse = response.asInstanceOf[StopReplicaResponse]
      val partitionErrorsForDeletingTopics = mutable.Map.empty[TopicPartition, Errors]
      stopReplicaResponse.partitionErrors.forEach { pe =>
        val tp = new TopicPartition(pe.topicName, pe.partitionIndex)
        if (controllerContext.isTopicDeletionInProgress(pe.topicName) &&
          (isPartitionDeleted(tp) || pe.deletePartition())) {
          partitionErrorsForDeletingTopics += tp -> Errors.forCode(pe.errorCode)
        }
      }
      if (partitionErrorsForDeletingTopics.nonEmpty)
        sendEvent(TopicDeletionStopReplicaResponseReceived(brokerId, stopReplicaResponse.error,
          partitionErrorsForDeletingTopics))
    }

    val maxBrokerEpoch = controllerContext.maxBrokerEpoch
    stopReplicaRequestMap.forKeyValue { (brokerId, partitionStates) =>
      if (controllerContext.liveOrShuttingDownBrokerIds.contains(brokerId)) {
        if (traceEnabled)
          partitionStates.forKeyValue { (topicPartition, partitionState) =>
            stateChangeLog.trace(s"Sending StopReplica request $partitionState to " +
              s"broker $brokerId for partition $topicPartition")
          }

        val brokerEpoch = controllerContext.liveBrokerIdAndEpochs(brokerId)
        if (stopReplicaRequestVersion >= 4) {
          val stopReplicaTopicState = mutable.Map.empty[String, StopReplicaTopicState]
          partitionStates.forKeyValue { (topicPartition, partitionState) =>
            val topicState = stopReplicaTopicState.getOrElseUpdate(topicPartition.topic,
              new StopReplicaTopicState().setTopicName(topicPartition.topic))
            topicState.partitionStates().add(partitionState)
          }

          stateChangeLog.info(s"Sending StopReplica request for ${partitionStates.size} " +
            s"replicas to broker $brokerId")
          val stopReplicaRequestBuilder = new StopReplicaRequest.Builder(
            stopReplicaRequestVersion, controllerId, controllerEpoch, brokerEpoch,
            maxBrokerEpoch, false, stopReplicaTopicState.values.toBuffer.asJava)
          sendRequest(brokerId, stopReplicaRequestBuilder,
            responseCallback(brokerId, tp => partitionStates.get(tp).exists(_.deletePartition)))
        } else {
          var numPartitionStateWithDelete = 0
          var numPartitionStateWithoutDelete = 0
          val topicStatesWithDelete = mutable.Map.empty[String, StopReplicaTopicState]
          val topicStatesWithoutDelete = mutable.Map.empty[String, StopReplicaTopicState]

          partitionStates.forKeyValue { (topicPartition, partitionState) =>
            val topicStates = if (partitionState.deletePartition()) {
              numPartitionStateWithDelete += 1
              topicStatesWithDelete
            } else {
              numPartitionStateWithoutDelete += 1
              topicStatesWithoutDelete
            }
            val topicState = topicStates.getOrElseUpdate(topicPartition.topic,
              new StopReplicaTopicState().setTopicName(topicPartition.topic))
            topicState.partitionStates().add(partitionState)
          }

          if (topicStatesWithDelete.nonEmpty) {
            stateChangeLog.info(s"Sending StopReplica request (delete = true) for " +
              s"$numPartitionStateWithDelete replicas to broker $brokerId")
            val stopReplicaRequestBuilder = new StopReplicaRequest.Builder(
              stopReplicaRequestVersion, controllerId, controllerEpoch, brokerEpoch,
              maxBrokerEpoch, true, topicStatesWithDelete.values.toBuffer.asJava)
            sendRequest(brokerId, stopReplicaRequestBuilder, responseCallback(brokerId, _ => true))
          }

          if (topicStatesWithoutDelete.nonEmpty) {
            stateChangeLog.info(s"Sending StopReplica request (delete = false) for " +
              s"$numPartitionStateWithoutDelete replicas to broker $brokerId")
            val stopReplicaRequestBuilder = new StopReplicaRequest.Builder(
              stopReplicaRequestVersion, controllerId, controllerEpoch, brokerEpoch,
              maxBrokerEpoch, false, topicStatesWithoutDelete.values.toBuffer.asJava)
            sendRequest(brokerId, stopReplicaRequestBuilder)
          }
        }
      }
    }

    stopReplicaRequestMap.clear()
  }

  def sendRequestsToBrokers(controllerEpoch: Int): Unit = {
    try {
      val stateChangeLog = stateChangeLogger.withControllerEpoch(controllerEpoch)
      sendLeaderAndIsrRequest(controllerEpoch, stateChangeLog)
      sendUpdateMetadataRequests(controllerEpoch, stateChangeLog)
      sendStopReplicaRequests(controllerEpoch, stateChangeLog)
    } catch {
      case e: Throwable =>
        if (leaderAndIsrRequestMap.nonEmpty) {
          error("Haven't been able to send leader and isr requests, current state of " +
            s"the map is $leaderAndIsrRequestMap. Exception message: $e")
        }
        if (updateMetadataRequestBrokerSet.nonEmpty) {
          error(s"Haven't been able to send metadata update requests to brokers $updateMetadataRequestBrokerSet, " +
            s"current state of the partition info is $updateMetadataRequestPartitionInfoMap. Exception message: $e")
        }
        if (stopReplicaRequestMap.nonEmpty) {
          error("Haven't been able to send stop replica requests, current state of " +
            s"the map is $stopReplicaRequestMap. Exception message: $e")
        }
        throw new IllegalStateException(e)
    }
  }
}

case class ControllerBrokerStateInfo(networkClient: NetworkClient,
                                     brokerNode: Node,
                                     messageQueue: BlockingQueue[QueueItem],
                                     requestSendThread: RequestSendThread,
                                     queueSizeGauge: Gauge[Int],
                                     requestRateAndTimeMetrics: Timer,
                                     reconfigurableChannelBuilder: Option[Reconfigurable])


class BrokerResponseTimeStats(val key: ApiKeys) extends KafkaMetricsGroup {
  // Records time for request waits on local send thread queue
  val brokerRequestQueueTime = newHistogram("brokerRequestQueueTimeMs", true, responseTimeTags)
  // Records time for controller to send request and receive response
  val brokerRequestRemoteTime = newHistogram("brokerRequestRemoteTimeMs", true, responseTimeTags)

  def responseTimeTags = Map("request" -> key.toString)

  def update(queueTime: Long, remoteTime: Long): Unit = {
    brokerRequestQueueTime.update(queueTime)
    brokerRequestRemoteTime.update(remoteTime)
  }

  def updateQueueTime(queueTime: Long): Unit = {
    brokerRequestQueueTime.update(queueTime)
  }

  def updateRemoteTime(queueTime: Long): Unit = {
    brokerRequestRemoteTime.update(queueTime)
  }

  def removeMetrics(): Unit = {
    removeMetric("brokerRequestQueueTimeMs", responseTimeTags)
    removeMetric("brokerRequestRemoteTimeMs", responseTimeTags)
  }
}