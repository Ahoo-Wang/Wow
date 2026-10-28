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

package me.ahoo.wow.query.memory

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.tck.EventQueryBackendSpec
import me.ahoo.wow.spi.log.EventStreamRecord
import java.util.concurrent.CopyOnWriteArrayList

/** The in-memory reference events backend passes the events backend contract (decision D235). */
class InMemoryEventQueryBackendTest : EventQueryBackendSpec() {
    private val streams = CopyOnWriteArrayList<EventStreamRecord>()
    private val storage = InMemoryEventQueryStorage({ type -> streams.filter { it.ref.type == type } })

    /** The memory backend reads streams, which hold no transport: it is left out. */
    override suspend fun store(streams: List<Pair<EventStreamRecord, ByteArray?>>) {
        this.streams += streams.map { it.first }
    }

    override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> = storage.binding(type)
}
