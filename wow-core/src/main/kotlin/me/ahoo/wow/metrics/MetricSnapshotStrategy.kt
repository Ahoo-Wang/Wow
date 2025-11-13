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

package me.ahoo.wow.metrics

import me.ahoo.wow.api.Wow
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.infra.Decorator
import reactor.core.publisher.Mono

/**
 * Metric decorator for snapshot strategies that collects metrics on snapshot strategy operations.
 * This class wraps a SnapshotStrategy and adds metrics collection with tags for aggregate name
 * to track snapshot strategy performance and success rates.
 *
 * @property delegate the underlying snapshot strategy implementation
 */
class MetricSnapshotStrategy(
    override val delegate: SnapshotStrategy
) : SnapshotStrategy,
    Decorator<SnapshotStrategy> {
    /**
     * Processes a state event exchange for snapshot strategy evaluation and collects metrics on the operation.
     * Metrics collected include timing, success/failure rates, and tags for aggregate identification.
     *
     * @param stateEventExchange the state event exchange to process
     * @return a Mono that completes when the event is processed
     */
    override fun onEvent(stateEventExchange: StateEventExchange<*>): Mono<Void> =
        delegate
            .onEvent(stateEventExchange)
            .name(Wow.WOW_PREFIX + "snapshot.event")
            .tag(Metrics.AGGREGATE_KEY, stateEventExchange.message.aggregateName)
            .metrics()
}
