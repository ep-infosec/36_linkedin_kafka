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
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.Metadata;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Meter;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.utils.LogContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConsumerMetadata extends Metadata {
    private final boolean includeInternalTopics;
    private final boolean allowAutoTopicCreation;
    private final SubscriptionState subscription;
    private final Set<String> transientTopics;
    private final Sensor metadataRequestRateSensor;
    private final Metrics metrics;

    public ConsumerMetadata(long refreshBackoffMs,
                            long metadataExpireMs,
                            boolean includeInternalTopics,
                            boolean allowAutoTopicCreation,
                            SubscriptionState subscription,
                            LogContext logContext,
                            ClusterResourceListeners clusterResourceListeners,
                            Metrics metrics) {
        this(refreshBackoffMs, metadataExpireMs, includeInternalTopics, allowAutoTopicCreation, subscription,
            logContext, clusterResourceListeners, metrics, Long.MAX_VALUE);
    }

    public ConsumerMetadata(long refreshBackoffMs,
        long metadataExpireMs,
        boolean includeInternalTopics,
        boolean allowAutoTopicCreation,
        SubscriptionState subscription,
        LogContext logContext,
        ClusterResourceListeners clusterResourceListeners,
        Metrics metrics,
        long clusterMetadataExpireMs) {
        super(refreshBackoffMs, metadataExpireMs, logContext, clusterResourceListeners, clusterMetadataExpireMs);
        this.includeInternalTopics = includeInternalTopics;
        this.allowAutoTopicCreation = allowAutoTopicCreation;
        this.subscription = subscription;
        this.transientTopics = new HashSet<>();
        this.metrics = metrics;
        this.metadataRequestRateSensor = metrics.sensor("consumer-metadata-request-rate");
        MetricName requestRate = metrics.metricName("consumer-metadata-request-rate",
            "consumer-metrics",
            "The average per-second number of metadata request sent by the consumer");
        MetricName requestTotal = metrics.metricName("consumer-metadata-request-total",
            "consumer-metrics",
            "The total number of metadata request sent by the consumer");

        this.metadataRequestRateSensor.add(new Meter(requestRate, requestTotal));
    }

    public boolean allowAutoTopicCreation() {
        return allowAutoTopicCreation;
    }

    public void recordMetadataRequest() {
        this.metadataRequestRateSensor.record();
    }

    @Override
    public synchronized MetadataRequest.Builder newMetadataRequestBuilder() {
        if (subscription.hasPatternSubscription())
            return MetadataRequest.Builder.allTopics();
        List<String> topics = new ArrayList<>();
        topics.addAll(subscription.metadataTopics());
        topics.addAll(transientTopics);
        return new MetadataRequest.Builder(topics, allowAutoTopicCreation);
    }

    synchronized void addTransientTopics(Set<String> topics) {
        this.transientTopics.addAll(topics);
        if (!fetch().topics().containsAll(topics))
            requestUpdateForNewTopics();
    }

    synchronized void clearTransientTopics() {
        this.transientTopics.clear();
    }

    @Override
    protected synchronized boolean retainTopic(String topic, boolean isInternal, long nowMs) {
        if (transientTopics.contains(topic) || subscription.needsMetadata(topic))
            return true;

        if (isInternal && !includeInternalTopics)
            return false;

        return subscription.matchesSubscribedPattern(topic);
    }
}