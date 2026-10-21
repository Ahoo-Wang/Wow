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

/** The 9.x store of [source] written in memory, keyed as 9.x keys streams: by instance id and version. */
class InMemoryNineXEventTarget(private val source: InMemoryNineXEventSource) : NineXEventTarget {
    /** The streams written, in order. */
    val written = mutableListOf<NineXStream>()

    private fun held(aggregate: NineXAggregate): List<NineXStream> =
        source.inventory().firstOrNull { it.aggregate == aggregate }?.let { _ ->
            source.aggregateIds(
                aggregate,
                null,
                Int.MAX_VALUE
            ).flatMap { source.streams(aggregate, it, 1, Int.MAX_VALUE) }
        }.orEmpty()

    override fun stored(
        aggregate: NineXAggregate,
        keys: Collection<NineXStreamKey>,
    ): Map<NineXStreamKey, NineXStoredStream> = held(aggregate)
        .associateBy { NineXStreamKey(it.aggregateId.id, it.version) }
        .filterKeys { it in keys }
        .mapValues { NineXStoredStream(it.value.aggregateId.tenantId, it.value.commandId) }

    override fun insert(aggregate: NineXAggregate, streams: List<NineXStream>): Set<NineXStreamKey> {
        val keys = held(aggregate).map { NineXStreamKey(it.aggregateId.id, it.version) }.toSet()
        val (duplicates, fresh) = streams.partition { NineXStreamKey(it.aggregateId.id, it.version) in keys }
        fresh.forEach {
            source.add(it)
            written += it
        }
        return duplicates.map { NineXStreamKey(it.aggregateId.id, it.version) }.toSet()
    }

    override fun revisionsOf(aggregate: NineXAggregate, name: String): List<String> =
        held(aggregate).flatMap { it.events }.filter { it.name == name }.map { it.revision }.distinct()

    override fun count(aggregate: NineXAggregate): Long = held(aggregate).size.toLong()
}
