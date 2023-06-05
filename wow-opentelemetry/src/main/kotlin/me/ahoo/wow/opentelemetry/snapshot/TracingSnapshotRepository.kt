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
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.opentelemetry.TraceMono
import reactor.core.publisher.Mono

class TracingSnapshotRepository(override val delegate: SnapshotRepository) :
    SnapshotRepository,
    Decorator<SnapshotRepository> {
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return Mono.defer {
            val parentContext = Context.current()
            val source = delegate.load<S>(aggregateId)
            TraceMono(parentContext, SnapshotRepositoryInstrumenter.LOAD_INSTRUMENTER, aggregateId, source)
        }
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return Mono.defer {
            val parentContext = Context.current()
            val source = delegate.save(snapshot)
            TraceMono(parentContext, SnapshotRepositoryInstrumenter.SAVE_INSTRUMENTER, snapshot.aggregateId, source)
        }
    }
}
