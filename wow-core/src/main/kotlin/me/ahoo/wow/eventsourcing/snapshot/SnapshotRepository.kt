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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Snapshot Repository.
 */
interface SnapshotRepository : Named, AggregateIdScanner {

    fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>>
    fun getVersion(aggregateId: AggregateId): Mono<Int> {
        return load<Any>(aggregateId)
            .map {
                it.version
            }
            .defaultIfEmpty(Version.UNINITIALIZED_VERSION)
    }

    fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void>
}

object NoOpSnapshotRepository : SnapshotRepository {
    const val NAME = "NoOp"
    override val name: String
        get() = NAME

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return Mono.empty()
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return Mono.empty()
    }

    override fun scanAggregateId(
        namedAggregate: NamedAggregate,
        afterId: String,
        limit: Int
    ): Flux<AggregateId> {
        return Flux.empty()
    }
}
