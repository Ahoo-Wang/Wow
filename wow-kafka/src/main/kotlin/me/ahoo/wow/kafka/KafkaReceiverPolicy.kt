/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.ahoo.wow.kafka

/**
 * Receive-side policy of the Kafka buses.
 *
 * - [prefetchBatches]: Kafka poll batches requested ahead of the downstream.
 * - [maxDeferredCommits]: acknowledged offsets kept for out-of-order commits. Offsets are committed only up to the
 *   earliest unacknowledged one, so an aggregate group finishing ahead of an earlier record never commits past it.
 *   Reactor Kafka stops polling while this many acknowledged offsets wait for a commit; the buses therefore also
 *   commit as soon as that many are waiting (Reactor Kafka's `commitBatchSize`, capped at this value), so the pause
 *   only lasts while an earlier record is still in flight, or a commit is.
 *
 * Receive retries follow the transport's [me.ahoo.wow.messaging.transport.TransportFailurePolicy].
 */
class KafkaReceiverPolicy(
    val prefetchBatches: Int = DEFAULT_PREFETCH_BATCHES,
    val maxDeferredCommits: Int = DEFAULT_MAX_DEFERRED_COMMITS,
) {
    init {
        require(prefetchBatches > 0) {
            "prefetchBatches must be greater than 0."
        }
        require(maxDeferredCommits > 0) {
            "maxDeferredCommits must be greater than 0 to preserve out-of-order acknowledgements."
        }
    }

    companion object {
        const val DEFAULT_PREFETCH_BATCHES: Int = 1

        /**
         * Kafka's default `max.poll.records`: one full poll can be acknowledged before a commit is due.
         * 9.2.2 and earlier used 1, which paused the consumer after every acknowledged record until the next
         * periodic commit (`commitInterval`, 5 s by default).
         */
        const val DEFAULT_MAX_DEFERRED_COMMITS: Int = 500
    }
}
