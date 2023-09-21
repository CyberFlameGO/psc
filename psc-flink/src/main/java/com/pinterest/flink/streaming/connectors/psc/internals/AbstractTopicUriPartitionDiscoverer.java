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

package com.pinterest.flink.streaming.connectors.psc.internals;

import org.apache.flink.annotation.Internal;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Base class for all partition discoverers.
 *
 * <p>This partition discoverer base class implements the logic around bookkeeping
 * discovered partitions, and using the information to determine whether or not there
 * are new partitions that the consumer subtask should subscribe to.
 *
 * <p>Subclass implementations should simply implement the logic of using the version-specific
 * PSC clients to fetch topic and partition metadata.
 *
 * <p>Since PSC clients are generally not thread-safe, partition discoverers should
 * not be concurrently accessed. The only exception for this would be the {@link #wakeup()}
 * call, which allows the discoverer to be interrupted during a {@link #discoverPartitions()} call.
 */
@Internal
public abstract class AbstractTopicUriPartitionDiscoverer {

    /**
     * Describes whether we are discovering partitions for fixed topics or a topic pattern.
     */
    private final PscTopicUrisDescriptor topicUrisDescriptor;

    /**
     * Index of the consumer subtask that this partition discoverer belongs to.
     */
    private final int indexOfThisSubtask;

    /**
     * The total number of consumer subtasks.
     */
    private final int numParallelSubtasks;

    /**
     * Flag to determine whether or not the discoverer is closed.
     */
    private volatile boolean closed = true;

    /**
     * Flag to determine whether or not the discoverer had been woken up.
     * When set to {@code true}, {@link #discoverPartitions()} would be interrupted as early as possible.
     * Once interrupted, the flag is reset.
     */
    private volatile boolean wakeup;

    /**
     * Map of topics to they're largest discovered partition id seen by this subtask.
     * This state may be updated whenever {@link AbstractTopicUriPartitionDiscoverer#discoverPartitions()} or
     * {@link AbstractTopicUriPartitionDiscoverer#setAndCheckDiscoveredPartition(PscTopicUriPartition)} is called.
     *
     * <p>This is used to remove old partitions from the fetched partition lists. It is sufficient
     * to keep track of only the largest partition id assuming backend topic URI partition numbers are only
     * allowed to be increased and has incremental ids.
     */
    private Set<PscTopicUriPartition> discoveredPartitions;

    public AbstractTopicUriPartitionDiscoverer(
            PscTopicUrisDescriptor topicUrisDescriptor,
            int indexOfThisSubtask,
            int numParallelSubtasks) {

        this.topicUrisDescriptor = checkNotNull(topicUrisDescriptor);
        this.indexOfThisSubtask = indexOfThisSubtask;
        this.numParallelSubtasks = numParallelSubtasks;
        this.discoveredPartitions = new HashSet<>();
    }

    /**
     * Opens the partition discoverer, initializing all required pubsub connections.
     *
     * <p>NOTE: thread-safety is not guaranteed.
     */
    public void open() throws Exception {
        closed = false;
        initializeConnections();
    }

    /**
     * Closes the partition discoverer, cleaning up all pubsub connections.
     *
     * <p>NOTE: thread-safety is not guaranteed.
     */
    public void close() throws Exception {
        closed = true;
        closeConnections();
    }

    /**
     * Interrupt an in-progress discovery attempt by throwing a {@link WakeupException}.
     * If no attempt is in progress, the immediate next attempt will throw a {@link WakeupException}.
     *
     * <p>This method can be called concurrently from a different thread.
     */
    public void wakeup() {
        wakeup = true;
        wakeupConnections();
    }

    /**
     * Execute a partition discovery attempt for this subtask.
     * This method lets the partition discoverer update what partitions it has discovered so far.
     *
     * @return List of discovered new partitions that this subtask should subscribe to.
     */
    public List<PscTopicUriPartition> discoverPartitions() throws WakeupException, ClosedException {
        if (!closed && !wakeup) {
            try {
                List<PscTopicUriPartition> newDiscoveredPartitions;

                // (1) get all possible partitions, based on whether we are subscribed to fixed topics or a topic pattern
                if (topicUrisDescriptor.isFixedTopicUris()) {
                    newDiscoveredPartitions = getAllPartitionsForTopicUris(topicUrisDescriptor.getFixedTopicUris());
                } else {
                    // TODO: getAllTopicUris() currently returns empty list in PscTopicUriPartitionDiscoverer due to backend consumer initialization process
                    List<String> matchedTopics = getAllTopicUris();

                    // retain topics that match the pattern
                    Iterator<String> iter = matchedTopics.iterator();
                    while (iter.hasNext()) {
                        if (!topicUrisDescriptor.isMatchingTopicUri(iter.next())) {
                            iter.remove();
                        }
                    }

                    if (matchedTopics.size() != 0) {
                        // get partitions only for matched topics
                        newDiscoveredPartitions = getAllPartitionsForTopicUris(matchedTopics);
                    } else {
                        newDiscoveredPartitions = null;
                    }
                }

                // (2) eliminate partition that are old partitions or should not be subscribed by this subtask
                if (newDiscoveredPartitions == null || newDiscoveredPartitions.isEmpty()) {
                    throw new RuntimeException("Unable to retrieve any partitions with PscTopicUrisDescriptor: " + topicUrisDescriptor);
                } else {
                    Iterator<PscTopicUriPartition> iter = newDiscoveredPartitions.iterator();
                    PscTopicUriPartition nextPartition;
                    while (iter.hasNext()) {
                        nextPartition = iter.next();
                        if (!setAndCheckDiscoveredPartition(nextPartition)) {
                            iter.remove();
                        }
                    }
                }

                return newDiscoveredPartitions;
            } catch (WakeupException e) {
                // the actual topic / partition metadata fetching methods
                // may be woken up midway; reset the wakeup flag and rethrow
                wakeup = false;
                throw e;
            }
        } else if (!closed && wakeup) {
            // may have been woken up before the method call
            wakeup = false;
            throw new WakeupException();
        } else {
            throw new ClosedException();
        }
    }

    /**
     * Sets a partition as discovered. Partitions are considered as new
     * if its partition id is larger than all partition ids previously
     * seen for the topic it belongs to. Therefore, for a set of
     * discovered partitions, the order that this method is invoked with
     * each partition is important.
     *
     * <p>If the partition is indeed newly discovered, this method also returns
     * whether the new partition should be subscribed by this subtask.
     *
     * @param partition the partition to set and check
     * @return {@code true}, if the partition wasn't seen before and should
     * be subscribed by this subtask; {@code false} otherwise
     */
    public boolean setAndCheckDiscoveredPartition(PscTopicUriPartition partition) {
        if (isUndiscoveredPartition(partition)) {
            discoveredPartitions.add(partition);

            return PscTopicUriPartitionAssigner.assign(partition, numParallelSubtasks) == indexOfThisSubtask;
        }

        return false;
    }

    // ------------------------------------------------------------------------
    //  PSC version specifics
    // ------------------------------------------------------------------------

    /**
     * Establish the required connections in order to fetch topics and partitions metadata.
     */
    protected abstract void initializeConnections() throws Exception;

    /**
     * Attempt to eagerly wakeup from blocking calls to PSC in {@link AbstractTopicUriPartitionDiscoverer#getAllTopicUris()}
     * and {@link AbstractTopicUriPartitionDiscoverer#getAllPartitionsForTopicUris(List)}.
     *
     * <p>If the invocation indeed results in interrupting an actual blocking PSC call, the implementations
     * of {@link AbstractTopicUriPartitionDiscoverer#getAllTopicUris()} and
     * {@link AbstractTopicUriPartitionDiscoverer#getAllPartitionsForTopicUris(List)} are responsible of throwing a
     * {@link WakeupException}.
     */
    protected abstract void wakeupConnections();

    /**
     * Close all established connections.
     */
    protected abstract void closeConnections() throws Exception;

    /**
     * Fetch the list of all topics from the pubsub.
     */
    protected abstract List<String> getAllTopicUris() throws WakeupException;

    /**
     * Fetch the list of all partitions for a specific topics list from the pubsub.
     */
    protected abstract List<PscTopicUriPartition> getAllPartitionsForTopicUris(List<String> topics) throws WakeupException;

    // ------------------------------------------------------------------------
    //  Utilities
    // ------------------------------------------------------------------------

    /**
     * Signaling exception to indicate that an actual PSC call was interrupted.
     */
    public static final class WakeupException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Thrown if this discoverer was used to discover partitions after it was closed.
     */
    public static final class ClosedException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private boolean isUndiscoveredPartition(PscTopicUriPartition partition) {
        return !discoveredPartitions.contains(partition);
    }
}
