/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.message.LeaderAndIsrRequestData;
import org.apache.kafka.common.message.LeaderAndIsrResponseData;
import org.apache.kafka.common.message.LiCombinedControlRequestData;
import org.apache.kafka.common.message.LiCombinedControlResponseData;
import org.apache.kafka.common.message.StopReplicaRequestData;
import org.apache.kafka.common.message.StopReplicaResponseData;
import org.apache.kafka.common.message.UpdateMetadataRequestData;



public class LiCombinedControlTransformer {
    /**
     * the LeaderAndIsrPartitionState in the LiCombinedControlRequest has one more field
     * than the LeaderAndIsrPartitionState in the LeaderAndIsr request, i.e. an extra broker epoch field.
     * Since one LiCombinedControlRequest may contain LeaderAndIsr partition states scattered across
     * multiple different broker epochs, we need to add the broker field to the partition level.
     *
     * @param partitionState the original partition state
     * @param brokerEpoch    the broker epoch in the original LeaderAndIsr request
     * @return the transformed partition state in the LiCombinedControlRequest
     */
    public static LiCombinedControlRequestData.LeaderAndIsrPartitionState transformLeaderAndIsrPartition(
            LeaderAndIsrRequestData.LeaderAndIsrPartitionState partitionState, long brokerEpoch) {
        return new LiCombinedControlRequestData.LeaderAndIsrPartitionState().setBrokerEpoch(brokerEpoch)
                .setTopicName(partitionState.topicName())
                .setPartitionIndex(partitionState.partitionIndex())
                .setControllerEpoch(partitionState.controllerEpoch())
                .setLeader(partitionState.leader())
                .setLeaderEpoch(partitionState.leaderEpoch())
                .setIsr(partitionState.isr())
                .setZkVersion(partitionState.zkVersion())
                .setReplicas(partitionState.replicas())
                .setAddingReplicas(partitionState.addingReplicas())
                .setRemovingReplicas(partitionState.removingReplicas())
                .setIsNew(partitionState.isNew());
    }

    public static LeaderAndIsrRequestData.LeaderAndIsrPartitionState restoreLeaderAndIsrPartition(
            LiCombinedControlRequestData.LeaderAndIsrPartitionState partitionState) {
        return new LeaderAndIsrRequestData.LeaderAndIsrPartitionState().setTopicName(partitionState.topicName())
                .setPartitionIndex(partitionState.partitionIndex())
                .setControllerEpoch(partitionState.controllerEpoch())
                .setLeader(partitionState.leader())
                .setLeaderEpoch(partitionState.leaderEpoch())
                .setIsr(partitionState.isr())
                .setZkVersion(partitionState.zkVersion())
                .setReplicas(partitionState.replicas())
                .setAddingReplicas(partitionState.addingReplicas())
                .setRemovingReplicas(partitionState.removingReplicas())
                .setIsNew(partitionState.isNew());
    }

    public static LiCombinedControlRequestData.UpdateMetadataPartitionState transformUpdateMetadataPartition(
            UpdateMetadataRequestData.UpdateMetadataPartitionState partitionState) {
        return new LiCombinedControlRequestData.UpdateMetadataPartitionState().setTopicName(partitionState.topicName())
                .setPartitionIndex(partitionState.partitionIndex())
                .setControllerEpoch(partitionState.controllerEpoch())
                .setLeader(partitionState.leader())
                .setLeaderEpoch(partitionState.leaderEpoch())
                .setIsr(partitionState.isr())
                .setZkVersion(partitionState.zkVersion())
                .setReplicas(partitionState.replicas())
                .setOfflineReplicas(partitionState.offlineReplicas());
    }

    public static UpdateMetadataRequestData.UpdateMetadataPartitionState restoreUpdateMetadataPartition(
            LiCombinedControlRequestData.UpdateMetadataPartitionState partitionState) {
        return new UpdateMetadataRequestData.UpdateMetadataPartitionState().setTopicName(partitionState.topicName())
                .setPartitionIndex(partitionState.partitionIndex())
                .setControllerEpoch(partitionState.controllerEpoch())
                .setLeader(partitionState.leader())
                .setLeaderEpoch(partitionState.leaderEpoch())
                .setIsr(partitionState.isr())
                .setZkVersion(partitionState.zkVersion())
                .setReplicas(partitionState.replicas())
                .setOfflineReplicas(partitionState.offlineReplicas());
    }

    public static LiCombinedControlRequestData.UpdateMetadataBroker transformUpdateMetadataBroker(UpdateMetadataRequestData.UpdateMetadataBroker broker) {
        List<LiCombinedControlRequestData.UpdateMetadataEndpoint> endpoints = broker.endpoints()
                .stream().map(endpoint ->
                        new LiCombinedControlRequestData.UpdateMetadataEndpoint().setHost(endpoint.host())
                                .setPort(endpoint.port())
                                .setListener(endpoint.listener())
                                .setSecurityProtocol(endpoint.securityProtocol())).collect(Collectors.toList());

        return new LiCombinedControlRequestData.UpdateMetadataBroker().setId(broker.id())
                .setEndpoints(endpoints)
                .setRack(broker.rack());
    }

    public static UpdateMetadataRequestData.UpdateMetadataBroker restoreUpdateMetadataBroker(
            LiCombinedControlRequestData.UpdateMetadataBroker broker) {
        List<UpdateMetadataRequestData.UpdateMetadataEndpoint> endpoints = broker.endpoints()
                .stream().map(endpoint ->
                        new UpdateMetadataRequestData.UpdateMetadataEndpoint().setHost(endpoint.host())
                                .setPort(endpoint.port())
                                .setListener(endpoint.listener())
                                .setSecurityProtocol(endpoint.securityProtocol())).collect(Collectors.toList());

        return new UpdateMetadataRequestData.UpdateMetadataBroker().setId(broker.id())
                .setEndpoints(endpoints)
                .setRack(broker.rack());
    }

    public static List<LiCombinedControlResponseData.LeaderAndIsrPartitionError> transformLeaderAndIsrPartitionErrors(
            List<LeaderAndIsrResponseData.LeaderAndIsrPartitionError> errors) {
        return errors.stream().map(error ->
                new LiCombinedControlResponseData.LeaderAndIsrPartitionError().setTopicName(error.topicName())
                        .setPartitionIndex(error.partitionIndex())
                        .setErrorCode(error.errorCode())).collect(Collectors.toList());
    }

    public static LiCombinedControlResponseData.LeaderAndIsrTopicErrorCollection transformLeaderAndIsrTopicErrors(LeaderAndIsrResponseData.LeaderAndIsrTopicErrorCollection sourceTopicErrors) {
        LiCombinedControlResponseData.LeaderAndIsrTopicErrorCollection transformedTopicErrors = new LiCombinedControlResponseData.LeaderAndIsrTopicErrorCollection();

        List<LiCombinedControlResponseData.LeaderAndIsrTopicError> topicErrorList = sourceTopicErrors.stream().map(topicError -> {
            List<LiCombinedControlResponseData.LeaderAndIsrPartitionError> partitionErrors = topicError.partitionErrors().stream().map(partitionError ->
                    new LiCombinedControlResponseData.LeaderAndIsrPartitionError().setTopicName(partitionError.topicName())
                            .setPartitionIndex(partitionError.partitionIndex())
                            .setErrorCode(partitionError.errorCode())).collect(Collectors.toList());

            return new LiCombinedControlResponseData.LeaderAndIsrTopicError().setTopicId(topicError.topicId())
                    .setPartitionErrors(partitionErrors);
        }).collect(Collectors.toList());
        transformedTopicErrors.addAll(topicErrorList);
        return transformedTopicErrors;
    }

    public static List<LeaderAndIsrResponseData.LeaderAndIsrPartitionError> restoreLeaderAndIsrPartitionErrors(
            List<LiCombinedControlResponseData.LeaderAndIsrPartitionError> errors) {
        return errors.stream().map(error ->
                new LeaderAndIsrResponseData.LeaderAndIsrPartitionError().setTopicName(error.topicName())
                        .setPartitionIndex(error.partitionIndex())
                        .setErrorCode(error.errorCode())).collect(Collectors.toList());
    }

    public static LeaderAndIsrResponseData.LeaderAndIsrTopicErrorCollection restoreLeaderAndIsrTopicErrors(
            LiCombinedControlResponseData.LeaderAndIsrTopicErrorCollection errors) {
        LeaderAndIsrResponseData.LeaderAndIsrTopicErrorCollection topicErrors = new LeaderAndIsrResponseData.LeaderAndIsrTopicErrorCollection();
        for (LiCombinedControlResponseData.LeaderAndIsrTopicError error : errors) {
            topicErrors.add(new LeaderAndIsrResponseData.LeaderAndIsrTopicError().setTopicId(error.topicId())
                    .setPartitionErrors(restoreLeaderAndIsrPartitionErrors(error.partitionErrors())));
        }
        return topicErrors;
    }

    public static List<LiCombinedControlResponseData.StopReplicaPartitionError> transformStopReplicaPartitionErrors(
        Map<TopicPartition, StopReplicaRequestData.StopReplicaPartitionState> partitionStates,
        List<StopReplicaResponseData.StopReplicaPartitionError> errors, int liCombinedControlRequestVersion) {
        return errors.stream().map(error -> {
            LiCombinedControlResponseData.StopReplicaPartitionError stopReplicaPartitionError =
                new LiCombinedControlResponseData.StopReplicaPartitionError().setTopicName(error.topicName())
                    .setPartitionIndex(error.partitionIndex())
                    .setErrorCode(error.errorCode());
            if (liCombinedControlRequestVersion >= 1) {
                StopReplicaRequestData.StopReplicaPartitionState partitionState =
                    partitionStates.get(new TopicPartition(error.topicName(), error.partitionIndex()));
                boolean deletePartition = false;
                if (partitionState != null) {
                    // Considering the partitions in the StopReplica response should always match that in
                    // the StopReplica request, we expect the partition to be always present in the
                    // partitionStates map and the partitionState variable to be always non-null.
                    // The check against null is to guard against unexpected mis-behaviors.
                    deletePartition = partitionState.deletePartition();
                }

                stopReplicaPartitionError.setDeletePartition(deletePartition);
            }
            return stopReplicaPartitionError;
        }).collect(Collectors.toList());
    }

    public static List<StopReplicaResponseData.StopReplicaPartitionError> restoreStopReplicaPartitionErrors(
            List<LiCombinedControlResponseData.StopReplicaPartitionError> errors) {
        return errors.stream().map(error ->
                new StopReplicaResponseData.StopReplicaPartitionError().setTopicName(error.topicName())
                        .setPartitionIndex(error.partitionIndex())
                        .setDeletePartition(error.deletePartition())
                        .setErrorCode(error.errorCode())).collect(Collectors.toList());
    }
}
