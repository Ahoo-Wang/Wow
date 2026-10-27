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

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.ElasticsearchStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.EventsQuerySchemas
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.tck.QueryBackendSpec
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * With Elasticsearch unreachable, the snapshot and the events queries both answer that the store is unavailable
 * (finding F23): a retryable 503, logged as a warning, not a broken schema answered 500 and logged as an error.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ElasticsearchUnreachableQueriesTest {
    /** Nothing listens on port 1 of this host: every request is refused. */
    private val rest = Rest5Client.builder(HttpHost.create("http://127.0.0.1:1")).build()
    private val client = ElasticsearchStore.client(rest)
    private val type = AggregateType(ElasticsearchFixture.newContext(), "order")

    @AfterAll
    fun close() {
        rest.close()
    }

    private suspend fun schemaOf(model: QueryModel, storage: QueryStorageAdapter) {
        val context = QuerySchemaContext(type, model)
        DefaultQueryModelSchemaProvider(
            context,
            listOf(BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, QueryBackendSpec.DECLARATION)))),
            storage,
        ).schema()
    }

    @Test
    fun `a snapshot query of an unreachable store is unavailable`(): Unit = runBlocking {
        val queries = ElasticsearchSnapshotQueries(client, ElasticsearchFixture.names)
        val failure = assertThrows<QueryStorageUnavailableException> {
            schemaOf(QueryModel.SNAPSHOT, queries.binding(type).storage)
        }
        failure.errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
    }

    @Test
    fun `an events query of an unreachable store is unavailable`(): Unit = runBlocking {
        val queries = ElasticsearchEventQueries(client, ElasticsearchFixture.names)
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    type.context,
                    aggregates = listOf(
                        AggregateManifest(
                            type.name,
                            state = StateManifest("#/\$defs/t.State"),
                            events = listOf(EventManifest("Placed", schema = "#/\$defs/t.Placed")),
                        ),
                    ),
                ),
            ),
            defs = mapOf("t.State" to mapOf("type" to "object"), "t.Placed" to mapOf("type" to "object")),
        )
        val failure = assertThrows<QueryStorageUnavailableException> {
            QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
                .compile(QuerySchemaContext(type, QueryModel.EVENTS), queries.binding(type).storage)
                .schema()
        }
        failure.errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
    }
}
