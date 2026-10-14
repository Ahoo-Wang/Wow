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

package me.ahoo.wow.migrate

import java.util.concurrent.CopyOnWriteArrayList

/** A 9.x store in memory, ordered as the MongoDB store reads: ids by (id, tenant), commits by (createTime, id). */
class InMemoryNineXEventSource(streams: Collection<NineXStream> = emptyList()) : NineXEventSource {
    private val streams = CopyOnWriteArrayList(streams)

    fun add(stream: NineXStream) {
        streams += stream
    }

    override fun inventory(): List<NineXInventory> = streams.groupBy { it.aggregate }.map { (aggregate, held) ->
        NineXInventory(
            aggregate = aggregate,
            streams = held.size.toLong(),
            events = held.flatMap { it.events }.groupingBy { NineXEventType(it.name, it.revision) }.eachCount()
                .mapValues { it.value.toLong() },
            commands = held.mapNotNull { it.commandName }.groupingBy { it }.eachCount().mapValues { it.value.toLong() },
            streamsWithoutCommand = held.count { it.commandName == null }.toLong(),
        )
    }

    override fun aggregateIds(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXAggregateId> =
        streams.filter { it.aggregate == aggregate && it.version == 1 }.map { it.aggregateId }.distinct().sorted()
            .filter { after == null || it > after }
            .take(limit)

    override fun streams(
        aggregate: NineXAggregate,
        id: NineXAggregateId,
        fromVersion: Int,
        limit: Int,
    ): List<NineXStream> = streams.filter { it.aggregate == aggregate && it.aggregateId == id && it.version >= fromVersion }
        .sortedBy { it.version }
        .take(limit)

    override fun committedSince(
        aggregate: NineXAggregate,
        from: Long,
        after: NineXPosition?,
        limit: Int,
    ): List<NineXStream> = streams.filter { it.aggregate == aggregate && it.createTime >= from }
        .filter {
            after == null || it.createTime > after.createTime ||
                (it.createTime == after.createTime && it.id > after.streamId)
        }
        .sortedWith(compareBy({ it.createTime }, { it.id }))
        .take(limit)
}
