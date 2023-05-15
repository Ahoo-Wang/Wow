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
package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

const val FIRST_CURSOR_ID = "(0)"

/**
 * Snapshot Repository.
 */
interface SnapshotRepository {
    @Deprecated(
        "use load(aggregateId: AggregateId): Mono<Snapshot<S>> instead.",
        ReplaceWith("load(aggregateId)")
    )
    fun <S : Any> load(metadata: StateAggregateMetadata<S>, aggregateId: AggregateId): Mono<Snapshot<S>> {
        return load(aggregateId)
    }

    fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>>
    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>
    fun findAggregateId(
        namedAggregate: NamedAggregate,
        cursorId: String = FIRST_CURSOR_ID,
        limit: Int = 10
    ): Flux<AggregateId>
}

object NoOpSnapshotRepository : SnapshotRepository {
    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return Mono.empty()
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return Mono.empty()
    }

    override fun findAggregateId(namedAggregate: NamedAggregate, cursorId: String, limit: Int): Flux<AggregateId> {
        return Flux.empty()
    }
}
