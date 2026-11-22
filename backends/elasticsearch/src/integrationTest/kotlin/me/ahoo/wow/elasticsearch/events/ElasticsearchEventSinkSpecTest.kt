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
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.tck.sink.SinkSpec
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/** The [SinkSpec] on the event search Sink: what a search of the events index finds, written again included. */
class ElasticsearchEventSinkSpecTest : SinkSpec() {
    override val aggregateType: AggregateType = AggregateType(ElasticsearchFixture.newContext(), "sinked")

    override fun createSink(): Sink = ElasticsearchEventSinks.of(ElasticsearchFixture.store())
        .sinkOf(aggregateType.context, aggregateType.name)

    override suspend fun written(ref: AggregateRef): List<Int> {
        val index = ElasticsearchFixture.names.events(ref.type.context, ref.type.name)
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
        return response.hits().hits().map { checkNotNull(it.source()).path("version").intValue() }
    }

    @Test
    fun `a stream of the longest id the rule takes, behind a long tenant, is written once whatever its retries`(): Unit =
        runBlocking {
            val ref = aggregateType.ref("t".repeat(LONG_TENANT), "购".repeat(170) + "xx")
            val sink = createSink()
            sink.write(listOf(record(ref, 1), record(ref, 2)))
            // Written again: the bounded ids are the same, so the streams already written are known and kept.
            sink.write(listOf(record(ref, 1), record(ref, 2), record(ref, 3)))
            val index = ElasticsearchFixture.names.events(ref.type.context, ref.type.name)
            ElasticsearchFixture.client.indices().refresh { it.index(index) }.await()
            written(ref).assert().containsExactly(1, 2, 3)
        }

    private companion object {
        const val MAX_HITS = 100
        const val LONG_TENANT = 64
    }
}
