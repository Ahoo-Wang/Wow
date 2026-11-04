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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test

/** A page of the walk as a store without a scan of its own reads it: [NineXEventSource.instances]'s default. */
class NineXEventSourceTest {
    private val order = NineXAggregate("example-service", "order")
    private val example = NineXExample.streams().filter { it.aggregate == order }
    private val other = example.map { it.copy(id = "${it.id}-z", aggregateId = it.aggregateId.copy(id = "z-order")) }
    private val source = InMemoryNineXEventSource(example + other)

    @Test
    fun `a page holds whole instances, and ends at the one that reaches its streams`() {
        source.instances(order, null, 10, 100).map { it.id.id to it.streams.size }.assert()
            .containsExactly(example.first().aggregateId.id to 4, "z-order" to 4)
        source.instances(order, null, 10, 6).also { page ->
            page.map { it.complete }.assert().containsExactly(true, false)
            page.last().streams.map { it.version }.assert().containsExactly(1, 2)
        }
        source.instances(order, null, 10, 4).map { it.id.id }.assert().containsExactly(example.first().aggregateId.id)
        source.instances(order, null, 1, 100).single().complete.assert().isTrue()
        source.instances(order, NineXAggregateId("z-order", "(0)"), 10, 100).assert().isEmpty()
    }

    @Test
    fun `a range of the walk reads its own instances, from its first, and ends where the range ends`() {
        val first = example.first().aggregateId.id
        source.instances(order, IdRange.ALL, null, 10, 100).assert().isEqualTo(source.instances(order, null, 10, 100))
        source.instances(order, IdRange(null, "z-order"), null, 1, 100).map { it.id.id }.assert().containsExactly(first)
        source.instances(order, IdRange(null, "z-order"), example.first().aggregateId, 10, 100).assert().isEmpty()
        source.instances(
            order,
            IdRange("z-order", null),
            null,
            1,
            100
        ).map { it.id.id }.assert().containsExactly("z-order")
        // Ids split by the walk's own: one boundary between two ids, none for one range or past the ids it holds.
        source.idBoundaries(order, 2).assert().containsExactly("z-order")
        source.idBoundaries(order, 1).assert().isEmpty()
        source.idBoundaries(order, 8).assert().containsExactly("z-order")
    }

    @Test
    fun `an instance whose streams do not read is left incomplete with none, for the import to read`() {
        val broken = object : NineXEventSource by source {
            override fun instances(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int, maxStreams: Int) =
                super<NineXEventSource>.instances(aggregate, after, limit, maxStreams)

            override fun streams(aggregate: NineXAggregate, id: NineXAggregateId, fromVersion: Int, limit: Int) =
                if (id.id == "z-order") {
                    throw MigrationException("9.x stream s-broken has no commandId.")
                } else {
                    source.streams(aggregate, id, fromVersion, limit)
                }
        }
        broken.instances(order, null, 10, 100).map { it.id.id to it.complete }.assert()
            .containsExactly(example.first().aggregateId.id to true, "z-order" to false)
        broken.instances(order, null, 10, 100).last().streams.assert().isEmpty()
    }
}
