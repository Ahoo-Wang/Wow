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

package me.ahoo.wow.elasticsearch.events

import co.elastic.clients.elasticsearch._types.SortOrder
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchAccessPresence
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.state.StateEventPayload
import me.ahoo.wow.spi.state.StateEventRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

/**
 * The access attributes of the events index: each document holds its record's with the version they are of, the
 * Sink stamps a change over the earlier documents, and a stamp writes none it would make older.
 */
class ElasticsearchEventSinkAccessTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "stamped")
    private val ref = type.ref("tenant", "a-1")

    private fun sink() = ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(type.context, type.name)

    private fun access(region: String) = mapOf("owner" to "alice", "space" to null, "region" to region)

    private fun record(
        version: Int,
        access: Map<String, Any?>?,
        accessVersion: Int? = version,
        ref: AggregateRef = this.ref,
    ) = StateEventRecord(
        ref = ref,
        version = version,
        commandId = "c-${ref.id}-$version",
        requestId = "r-${ref.id}-$version",
        commandType = "${type.context}.${type.name}.Act",
        commitTime = version.toLong(),
        events = listOf(StateEventPayload("${type.context}.${type.name}.Acted", 1, WowJson.mapper.readTree("{}"))),
        access = access,
        accessVersion = accessVersion,
    )

    /** The attributes of each document of [ref] and the version they are of, in version order. */
    private fun documents(ref: AggregateRef = this.ref): List<Pair<Map<String, Any?>?, Int?>> = runBlocking {
        val index = ElasticsearchFixture.names.events(type.context, type.name)
        ElasticsearchFixture.client.indices().refresh { it.index(index) }.await()
        val response = ElasticsearchFixture.client.search({ search ->
            search.index(index).size(MAX_HITS)
                .query { query ->
                    query.bool { bool ->
                        bool.filter { it.term { term -> term.field("tenantId").value(ref.tenantId) } }
                            .filter { it.term { term -> term.field("aggregateId").value(ref.id) } }
                    }
                }
                .sort { it.field { field -> field.field("version").order(SortOrder.Asc) } }
        }, ObjectNode::class.java).await()
        response.hits().hits().map { hit ->
            val source = checkNotNull(hit.source())
            AccessValues.of(source.get("access")) to source.get("accessVersion")?.intValue()
        }
    }

    /** The attributes each document of [ref] holds as present ([ElasticsearchAccessPresence]), in version order. */
    private fun presence(ref: AggregateRef = this.ref): List<Set<String>?> = runBlocking {
        val index = ElasticsearchFixture.names.events(type.context, type.name)
        ElasticsearchFixture.client.indices().refresh { it.index(index) }.await()
        val response = ElasticsearchFixture.client.search({ search ->
            search.index(index).size(MAX_HITS)
                .query { query -> query.term { term -> term.field("aggregateId").value(ref.id) } }
                .sort { it.field { field -> field.field("version").order(SortOrder.Asc) } }
        }, ObjectNode::class.java).await()
        response.hits().hits().map { hit ->
            checkNotNull(hit.source()).get(ElasticsearchAccessPresence.FIELD)?.properties()?.map { it.key }?.toSet()
        }
    }

    @Test
    fun `each write of the attributes records which are present, an empty one among them`(): Unit = runBlocking {
        val sink = sink()
        val shared = mapOf("owner" to "alice", "space" to null, "sharedWith" to listOf("bob"))
        val unshared = mapOf("owner" to "alice", "space" to null, "sharedWith" to emptyList<String>())
        sink.write(listOf(record(1, null, null), record(2, null, null)))
        presence().assert().containsExactly(null, null)
        // The wow.access follower's stamp of a change.
        sink.stamp(ref, shared, asOf = 2, from = 1, until = 3).assert().isEqualTo(2)
        presence().assert().containsExactly(setOf("owner", "sharedWith"), setOf("owner", "sharedWith"))
        // The walk's page of stamps: the set is empty now, and still present.
        sink.stampAll(listOf(AccessStamp(ref, unshared, asOf = 3, from = 1, until = 3))).assert().isEqualTo(2)
        presence().assert().containsExactly(setOf("owner", "sharedWith"), setOf("owner", "sharedWith"))
        // A stream the Sink writes with its attributes.
        sink.write(listOf(record(3, unshared)))
        presence().last().assert().isEqualTo(setOf("owner", "sharedWith"))
    }

    @Test
    fun `a record that changed the attributes stamps the earlier documents, and no older stamp writes over it`(): Unit =
        runBlocking {
            val sink = sink()
            sink.write(listOf(record(1, access("east")), record(2, access("east")), record(3, access("west"))))
            documents().assert().containsExactly(access("west") to 3, access("west") to 3, access("west") to 3)
            // Nothing changed.
            sink.write(listOf(record(4, access("west"))))
            documents().map { it.second }.assert().containsExactly(3, 3, 3, 4)
            // A replay of an older change writes nothing, an empty range neither; another type's aggregate is not this Sink's.
            sink.stamp(ref, access("east"), asOf = 2, from = 1, until = 3).assert().isEqualTo(0)
            sink.stamp(ref, access("east"), asOf = 9, from = 3, until = 3).assert().isEqualTo(0)
            assertThrows<IllegalArgumentException> {
                sink.stamp(AggregateType(type.context, "other").ref("tenant", "x"), access("east"), 9, 1, 2)
            }
            documents().first().assert().isEqualTo(access("west") to 3)
            // A stamp as of the same version writes other attributes again: a new model's.
            sink.stamp(ref, access("north"), asOf = 4, from = 1, until = 4).assert().isEqualTo(3)
            documents().assert().containsExactly(
                access("north") to 4,
                access("north") to 4,
                access("north") to 4,
                access("west") to 4,
            )
        }

    @Test
    fun `a walk's page of stamps is one update by query, each document its own aggregate's stamp under its guard`(): Unit =
        runBlocking {
            val sink = sink()
            val other = type.ref("tenant", "a-2")
            sink.write((1..3).map { record(it, null, null) } + (1..2).map { record(it, null, null, other) })
            // A later stamp of a-2's first document stays.
            sink.stamp(other, access("west"), asOf = 9, from = 1, until = 2).assert().isEqualTo(1)
            sink.stampAll(
                listOf(
                    AccessStamp(ref, access("east"), asOf = 3, from = 1, until = 3),
                    AccessStamp(ref, access("east"), asOf = 3, from = 3, until = 4),
                    AccessStamp(other, access("north"), asOf = 2, from = 1, until = 3),
                ),
            ).assert().isEqualTo(4)
            documents().assert().containsExactly(access("east") to 3, access("east") to 3, access("east") to 3)
            documents(other).assert().containsExactly(access("west") to 9, access("north") to 2)
            // Stamps of one aggregate of different versions are written one at a time: the later one stays.
            sink.stampAll(
                listOf(
                    AccessStamp(ref, access("west"), asOf = 5, from = 1, until = 2),
                    AccessStamp(ref, access("north"), asOf = 4, from = 2, until = 4),
                ),
            ).assert().isEqualTo(3)
            documents().assert().containsExactly(access("west") to 5, access("north") to 4, access("north") to 4)
            sink.stampAll(emptyList()).assert().isEqualTo(0)
        }

    @Test
    fun `a stream read between two chunks of the log's stamp keeps the documents of the later version`(): Unit =
        runBlocking {
            val sink = sink()
            // The commit at 6 changed the attributes to west; the log stamps them over 1 to 5 in chunks of two. The
            // Sink reads 1 and 2 after the first chunk, as of 6, and 3 before the second, as of its own version.
            sink.write(
                listOf(record(1, access("west"), accessVersion = 6), record(2, access("west"), accessVersion = 6))
            )
            sink.write(listOf(record(3, access("east"))))
            // East differs from 2's west, so the Sink stamps it over 1 and 2 as of 3: their stamp is of 6, later.
            documents().assert().containsExactly(access("west") to 6, access("west") to 6, access("east") to 3)
            // The rest read after the stamp, and the commit at 6 itself.
            sink.write((4..6).map { record(it, access("west"), accessVersion = 6) })
            documents().assert().containsExactly(*Array(6) { access("west") to 6 })
        }

    @Test
    fun `the stream before a record the Sink has not seen is read from its document, one without attributes too`(): Unit =
        runBlocking {
            sink().write(listOf(record(1, access("east"))))
            // Another node's Sink, after a restart: it knows nothing of the aggregate.
            sink().write(listOf(record(2, access("west"))))
            documents().assert().containsExactly(access("west") to 2, access("west") to 2)
            // A document of an earlier version holds none.
            val other = type.ref("tenant", "a-2")
            sink().write(listOf(record(1, null, ref = other)))
            documents(other).assert().containsExactly(null to null)
            sink().write(listOf(record(2, access("south"), ref = other)))
            documents(other).assert().containsExactly(access("south") to 2, access("south") to 2)
        }

    private companion object {
        const val MAX_HITS = 100
    }
}
