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

import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.FilterType
import reactor.core.publisher.Mono

interface SnapshotSink : AutoCloseable {
    fun sink(snapshot: Snapshot<*>): Mono<Void>
    override fun close() = Unit
}

object NoOpSnapshotSink : SnapshotSink {
    override fun sink(snapshot: Snapshot<*>): Mono<Void> {
        return Mono.empty()
    }
}

@FilterType(SnapshotDispatcher::class)
@Order(ORDER_LAST)
class SnapshotSinkFilter(
    private val snapshotSink: SnapshotSink
) : Filter<EventStreamExchange> {
    override fun filter(exchange: EventStreamExchange, next: FilterChain<EventStreamExchange>): Mono<Void> {
        return Mono.defer {
            val snapshot = exchange.getSnapshot<Any>() ?: return@defer Mono.empty<Void>()
            snapshotSink.sink(snapshot)
        }
    }
}
