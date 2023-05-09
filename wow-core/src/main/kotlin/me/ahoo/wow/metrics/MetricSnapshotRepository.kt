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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.infra.Decorator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class MetricSnapshotRepository(override val delegate: SnapshotRepository) :
    SnapshotRepository,
    Decorator<SnapshotRepository> {
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return delegate.load<S>(aggregateId)
            .name(Wow.WOW_PREFIX + "snapshot.load")
            .tag(Metrics.AGGREGATE_KEY, aggregateId.aggregateName)
            .metrics()
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return delegate.save(snapshot)
            .name(Wow.WOW_PREFIX + "snapshot.save")
            .tag(Metrics.AGGREGATE_KEY, snapshot.aggregateId.aggregateName)
            .metrics()
    }

    override fun findAggregateId(namedAggregate: NamedAggregate, cursorId: String, limit: Int): Flux<AggregateId> {
        return delegate.findAggregateId(namedAggregate = namedAggregate, cursorId = cursorId, limit = limit)
            .name(Wow.WOW_PREFIX + "snapshot.findAggregateId")
            .tag(Metrics.AGGREGATE_KEY, namedAggregate.aggregateName)
            .metrics()
    }
}
