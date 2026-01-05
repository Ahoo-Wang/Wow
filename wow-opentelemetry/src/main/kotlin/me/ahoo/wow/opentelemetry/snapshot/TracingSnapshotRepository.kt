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

package me.ahoo.wow.opentelemetry.snapshot

import io.opentelemetry.context.Context
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.opentelemetry.TraceMono
import me.ahoo.wow.opentelemetry.Traced
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class TracingSnapshotRepository(override val delegate: SnapshotRepository) :
    Traced,
    SnapshotRepository,
    Decorator<SnapshotRepository> {
    override val name: String
        get() = delegate.name

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return Mono.defer {
            val parentContext = Context.current()
            val source = delegate.load<S>(aggregateId)
            TraceMono(parentContext, SnapshotRepositoryInstrumenter.LOAD_INSTRUMENTER, aggregateId, source)
        }
    }

    override fun getVersion(aggregateId: AggregateId): Mono<Int> {
        return delegate.getVersion(aggregateId)
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return Mono.defer {
            val parentContext = Context.current()
            val source = delegate.save(snapshot)
            TraceMono(parentContext, SnapshotRepositoryInstrumenter.SAVE_INSTRUMENTER, snapshot.aggregateId, source)
        }
    }

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> {
        return delegate.scanAggregateId(namedAggregate, afterId, limit)
    }
}
