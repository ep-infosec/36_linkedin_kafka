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
package org.apache.kafka.server.log.remote.metadata.storage;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.server.log.remote.storage.RemoteLogMetadataManager;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadataUpdate;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteStorageException;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class TopicBasedRemoteLogMetadataManagerWrapperWithHarness implements RemoteLogMetadataManager {

    private final TopicBasedRemoteLogMetadataManagerHarness harness = new TopicBasedRemoteLogMetadataManagerHarness();

    @Override
    public void addRemoteLogSegmentMetadata(RemoteLogSegmentMetadata remoteLogSegmentMetadata) throws RemoteStorageException {
        delegate().addRemoteLogSegmentMetadata(remoteLogSegmentMetadata);
    }

    @Override
    public void updateRemoteLogSegmentMetadata(RemoteLogSegmentMetadataUpdate remoteLogSegmentMetadataUpdate) throws RemoteStorageException {
        delegate().updateRemoteLogSegmentMetadata(remoteLogSegmentMetadataUpdate);
    }

    @Override
    public Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(TopicIdPartition topicIdPartition,
                                                                       int epochForOffset,
                                                                       long offset) throws RemoteStorageException {
        return delegate().remoteLogSegmentMetadata(topicIdPartition, epochForOffset, offset);
    }

    @Override
    public Optional<Long> highestOffsetForEpoch(TopicIdPartition topicIdPartition,
                                                int leaderEpoch) throws RemoteStorageException {
        return delegate().highestOffsetForEpoch(topicIdPartition, leaderEpoch);
    }

    @Override
    public void putRemotePartitionDeleteMetadata(RemotePartitionDeleteMetadata remotePartitionDeleteMetadata) throws RemoteStorageException {
        delegate().putRemotePartitionDeleteMetadata(remotePartitionDeleteMetadata);
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(TopicIdPartition topicIdPartition) throws RemoteStorageException {
        return delegate().listRemoteLogSegments(topicIdPartition);
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(TopicIdPartition topicIdPartition,
                                                                    int leaderEpoch) throws RemoteStorageException {
        return delegate().listRemoteLogSegments(topicIdPartition, leaderEpoch);
    }

    @Override
    public void onPartitionLeadershipChanges(Set<TopicIdPartition> leaderPartitions,
                                             Set<TopicIdPartition> followerPartitions) {
        delegate().onPartitionLeadershipChanges(leaderPartitions, followerPartitions);
    }

    @Override
    public void onStopPartitions(Set<TopicIdPartition> partitions) {
        delegate().onStopPartitions(partitions);
    }

    @Override
    public void close() throws IOException {
        harness.close();
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // This will make sure the cluster is up and TopicBasedRemoteLogMetadataManager is initialized.
        harness.initialize(Collections.emptySet(), true);
        delegate().configure(configs);
    }
    
    private RemoteLogMetadataManager delegate() {
        return harness.remoteLogMetadataManager();
    }
}
