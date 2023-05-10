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
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.infra.Decorator
import reactor.core.publisher.Mono

class MetricSnapshotStrategy(override val delegate: SnapshotStrategy) : SnapshotStrategy, Decorator<SnapshotStrategy> {
    override fun onEvent(eventStream: DomainEventStream): Mono<Void> {
        return delegate.onEvent(eventStream)
            .name(Wow.WOW_PREFIX + "snapshot.event")
            .tag(Metrics.AGGREGATE_KEY, eventStream.aggregateName)
            .metrics()
    }
}
