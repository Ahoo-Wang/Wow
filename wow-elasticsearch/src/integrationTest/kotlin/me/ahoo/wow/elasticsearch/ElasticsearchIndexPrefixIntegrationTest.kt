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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest
import co.elastic.clients.elasticsearch.indices.DeleteIndexTemplateRequest
import co.elastic.clients.elasticsearch.indices.ExistsIndexTemplateRequest
import co.elastic.clients.elasticsearch.indices.ExistsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.TemplateInitializer.createElasticsearchTemplate
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackendFactory
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.target
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Two deployments with different index prefixes on one cluster, beside Wow's unprefixed templates: each puts its own
 * templates, writes and reads its own indices, and never sees the other's documents.
 */
class ElasticsearchIndexPrefixIntegrationTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    @TempDir
    lateinit var tempDir: Path

    private lateinit var client: ReactiveElasticsearchClient
    private val run = generateGlobalId().lowercase()
    private val first = ElasticsearchIndexNaming("it-$run-a.")
    private val second = ElasticsearchIndexNaming("it-$run-b.")

    @BeforeEach
    fun setup() {
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        // Wow's unprefixed templates may already be on the cluster: the prefixed ones must not overlap them.
        client.initSnapshotTemplate()
        client.initEventStreamTemplate()
        val operations = client.createElasticsearchTemplate()
        listOf(first, second).forEach { IndexTemplateInitializer(operations, it).ensureAllTemplates().block() }
    }

    @AfterEach
    fun cleanup() {
        listOf(first, second).forEach { naming ->
            listOf(
                naming.snapshotIndexName(MOCK_AGGREGATE_METADATA),
                naming.eventStreamIndexName(MOCK_AGGREGATE_METADATA),
            ).filter { indexExists(it) }
                .forEach { index -> client.indices().delete(DeleteIndexRequest.of { it.index(index) }).block() }
            listOf("wow-snapshot-template", "wow-event-stream-template").map(naming::resolve).forEach { name ->
                client.indices().deleteIndexTemplate(DeleteIndexTemplateRequest.of { it.name(name) }).block()
            }
        }
    }

    private fun indexExists(name: String): Boolean =
        client.indices().exists(ExistsRequest.of { it.index(name) }).block()!!.value()

    private fun templateExists(name: String): Boolean =
        client.indices().existsIndexTemplate(ExistsIndexTemplateRequest.of { it.name(name) }).block()!!.value()

    @Test
    fun `each prefix keeps its own templates, indices and documents`() {
        listOf(first, second).forEach { naming ->
            templateExists(naming.resolve("wow-snapshot-template")).assert().isTrue()
            templateExists(naming.resolve("wow-event-stream-template")).assert().isTrue()
        }
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId(), tenantId = "tenant")
        val firstStream = generateEventStream(aggregateId, aggregateVersion = 0, eventCount = 1)
        val secondStream = generateEventStream(aggregateId, aggregateVersion = 0, eventCount = 2)

        // The same aggregate at the same version: on one index the second append would be a version conflict.
        ElasticsearchEventStore(client, indexNaming = first).use { it.append(firstStream).block() }
        ElasticsearchEventStore(client, indexNaming = second).use { it.append(secondStream).block() }

        ElasticsearchEventStore(client, indexNaming = first).use { store ->
            store.load(aggregateId).map { it.id }.test().expectNext(firstStream.id).verifyComplete()
        }
        ElasticsearchEventStore(client, indexNaming = second).use { store ->
            store.load(aggregateId).map { it.id }.test().expectNext(secondStream.id).verifyComplete()
        }
        ElasticsearchEventStore(client).use { store -> store.load(aggregateId).test().verifyComplete() }

        // The prefixed template applied to the prefixed index.
        val eventIndex = first.eventStreamIndexName(MOCK_AGGREGATE_METADATA)
        client.indices().getMapping(GetMappingRequest.of { it.index(eventIndex) }).block()!!
            .mappings().getValue(eventIndex).mappings().properties().getValue("aggregateId").isKeyword
            .assert().isTrue()

        val state = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        ElasticsearchSnapshotStore(client, indexNaming = first).use {
            it.save(SimpleSnapshot(state, Clock.systemUTC().millis())).block()
        }
        ElasticsearchSnapshotStore(client, indexNaming = first).use { store ->
            store.load<MockStateAggregate>(aggregateId).map(Snapshot<MockStateAggregate>::aggregateId)
                .test().expectNext(aggregateId).verifyComplete()
        }
        ElasticsearchSnapshotStore(client, indexNaming = second).use { store ->
            store.load<MockStateAggregate>(aggregateId).test().verifyComplete()
        }

        // The query side reads the prefixed index, its schema simulated from the prefixed template while it is missing.
        val filter = filterExpression { aggregateId(aggregateId.id) }
        listOf(first to 1L, second to 0L).forEach { (naming, expected) ->
            val target = ElasticsearchSnapshotQueryBackendFactory(client, indexNaming = naming)
                .target(MOCK_AGGREGATE_METADATA)
            val schema = target.schemaProvider.schema().block()!!
            schema.field(QueryField("tenantId"))?.binding(QueryCapability.EXACT_MATCH).assert().isNotNull()
            target.backend.count(QueryAdmission.Trusted.count(filter, schema))
                .test().expectNext(expected).verifyComplete()
        }
        indexExists(second.snapshotIndexName(MOCK_AGGREGATE_METADATA)).assert().isFalse()
    }

    @Test
    fun `a shipped definition creates the prefixed index`() {
        val definitionName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val file = tempDir.resolve("wow/elasticsearch/$definitionName.json")
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"mappings":{"properties":{"state":{"properties":{"status":{"type":"keyword"}}}}}}""",
        )

        ElasticsearchSnapshotIndexInitializer(
            client,
            WowResourceLocator(configDirectory = tempDir),
            listOf(MOCK_AGGREGATE_METADATA),
            first,
        ).ensureAll().block()

        val index = first.snapshotIndexName(MOCK_AGGREGATE_METADATA)
        val properties = client.indices().getMapping(GetMappingRequest.of { it.index(index) }).block()!!
            .mappings().getValue(index).mappings().properties()
        // The definition's field and the prefixed template's beneath it.
        properties.getValue("state").`object`().properties().getValue("status").isKeyword.assert().isTrue()
        properties.getValue("aggregateId").isKeyword.assert().isTrue()
        indexExists(second.snapshotIndexName(MOCK_AGGREGATE_METADATA)).assert().isFalse()
    }
}
