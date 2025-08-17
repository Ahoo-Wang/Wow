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
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import java.util.concurrent.ConcurrentHashMap

class InMemorySnapshotRepository : SnapshotRepository {
    companion object {
        const val NAME = "in_memory"
    }

    override val name: String
        get() = NAME
    private val aggregateIdMapSnapshot = ConcurrentHashMap<AggregateId, String>()

    override fun <S : Any> load(aggregateId: AggregateId): Mono<Snapshot<S>> {
        return Mono.fromCallable {
            aggregateIdMapSnapshot[aggregateId]?.toObject<Snapshot<S>>()
        }
    }

    override fun <S : Any> save(snapshot: Snapshot<S>): Mono<Void> {
        return Mono.fromRunnable {
            aggregateIdMapSnapshot[snapshot.aggregateId] = snapshot.toJsonString()
        }
    }

    override fun scanAggregateId(namedAggregate: NamedAggregate, afterId: String, limit: Int): Flux<AggregateId> {
        return aggregateIdMapSnapshot.keys.sortedBy { it.id }.toFlux()
            .filter {
                it.id > afterId
            }
            .take(limit.toLong())
    }
}
