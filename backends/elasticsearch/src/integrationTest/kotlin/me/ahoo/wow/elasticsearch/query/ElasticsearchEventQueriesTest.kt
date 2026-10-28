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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.mapping.Property
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.ElasticsearchStoreProvider
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventSinks
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.store.facet
import org.junit.jupiter.api.Test

/** The events indices beyond the contract: the body mappings, the DDL modes, an index the Sink has not created yet. */
class ElasticsearchEventQueriesTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "order")
    private val index = ElasticsearchFixture.names.events(type.context, type.name)

    /** Two events that declare `tags` and `detail` alike in kind, and `kind` with different types. */
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                type.context,
                aggregates = listOf(
                    AggregateManifest(
                        type.name,
                        state = StateManifest("#/\$defs/t.State"),
                        events = listOf(
                            EventManifest("Tagged", schema = "#/\$defs/t.Tagged"),
                            EventManifest("Retagged", schema = "#/\$defs/t.Retagged"),
                        ),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "t.State" to mapOf("type" to "object"),
            "t.Tagged" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "tags" to mapOf("type" to "array", "description" to "a", "items" to mapOf("type" to "string")),
                    "detail" to mapOf("type" to "object", "properties" to mapOf("x" to mapOf("type" to "integer"))),
                    "kind" to mapOf("type" to "string"),
                ),
            ),
            "t.Retagged" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "tags" to mapOf("type" to "array", "description" to "b", "items" to mapOf("type" to "string")),
                    "detail" to mapOf("type" to "object", "properties" to mapOf("y" to mapOf("type" to "string"))),
                    "kind" to mapOf("type" to "integer"),
                ),
            ),
        ),
    )

    private fun schema(queries: ElasticsearchEventQueries): QueryModelSchema = runBlocking {
        QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
            .compile(QuerySchemaContext(type, QueryModel.EVENTS), queries.binding(type).storage)
            .schema()
    }

    private suspend fun fields() = ElasticsearchIndexMappingResolver(ElasticsearchFixture.client).refresh(index).fields

    private suspend fun createIndex() {
        ElasticsearchEventSinks.of(ElasticsearchFixture.store()).sinkOf(type.context, type.name).prepare()
    }

    @Test
    fun `the events' body fields are mapped once each, those of different types not at all`(): Unit = runBlocking {
        createIndex()
        schema(ElasticsearchEventQueries(ElasticsearchFixture.client, ElasticsearchFixture.names))
        val fields = fields()
        fields.getValue("events").kind.assert().isEqualTo(Property.Kind.Nested)
        fields.getValue("events.body.tags").kind.assert().isEqualTo(Property.Kind.Keyword)
        fields.getValue("events.body.detail.x").kind.assert().isEqualTo(Property.Kind.Long)
        fields.getValue("events.body.detail.y").kind.assert().isEqualTo(Property.Kind.Keyword)
        fields.containsKey("events.body.kind").assert().isFalse()
    }

    @Test
    fun `under validate no body field is mapped`(): Unit = runBlocking {
        createIndex()
        schema(ElasticsearchEventQueries(ElasticsearchFixture.client, ElasticsearchFixture.names, Ddl.VALIDATE))
        fields().keys.none { it.startsWith("events.body.") }.assert().isTrue()
    }

    @Test
    fun `before the Sink's first write the queries answer nothing`(): Unit = runBlocking {
        val queries = checkNotNull(ElasticsearchFixture.store().facet<StoreQueries>())
            .events(StoreQueryOptions.DEFAULT) as ElasticsearchEventQueries
        val schema = schema(queries)
        val backend = queries.binding(type).backend
        backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList().assert().isEmpty()
        backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema)).count.assert()
            .isEqualTo(0L)
    }

    @Test
    fun `a Sink under validate requires its index`() {
        val store = ElasticsearchStoreProvider().open(ElasticsearchFixture.config(Ddl.VALIDATE))
        val sink = ElasticsearchEventSinks.of(store).sinkOf(type.context, type.name)
        assertThrownBy<IllegalStateException> { runBlocking { sink.prepare() } }
            .hasMessageContaining(index)
    }
}
