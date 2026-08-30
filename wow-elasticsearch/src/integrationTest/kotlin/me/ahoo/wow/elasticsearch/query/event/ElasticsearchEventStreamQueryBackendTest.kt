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

package me.ahoo.wow.elasticsearch.query.event

import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.ScriptLanguage
import co.elastic.clients.elasticsearch.core.UpdateRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.eventsourcing.toDocId
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.query.EventStreamQueryBackendSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import java.time.Duration

class ElasticsearchEventStreamQueryBackendTest : EventStreamQueryBackendSpec() {
    override val cursorQuerySupported: Boolean = true

    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    lateinit var elasticsearchClient: ReactiveElasticsearchClient

    @BeforeEach
    override fun setup() {
        elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        elasticsearchClient.initEventStreamTemplate()
        super.setup()
    }

    override fun createEventStore(): EventStore {
        return ElasticsearchEventStore(elasticsearchClient)
    }

    override fun createEventStreamQueryBackendFactory(): EventStreamQueryBackendFactory {
        return ElasticsearchEventStreamQueryBackendFactory(elasticsearchClient)
    }

    @Suppress("UNCHECKED_CAST")
    override fun prepareNullAndMissingCursorEventStreams(
        nullStream: DomainEventStream,
        missingStream: DomainEventStream,
    ) {
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(nullStream.aggregateId.toEventStreamIndexName())
                    .id(nullStream.toDocId())
                    .doc(mapOf("ownerId" to null))
                    .refresh(Refresh.True)
            },
            Map::class.java as Class<Map<String, Any?>>,
        ).block()
        elasticsearchClient.update(
            UpdateRequest.of<Map<String, Any?>, Map<String, Any?>> { request ->
                request.index(missingStream.aggregateId.toEventStreamIndexName())
                    .id(missingStream.toDocId())
                    .script { script ->
                        script.lang(ScriptLanguage.Painless)
                            .source { source -> source.scriptString("ctx._source.remove('ownerId')") }
                    }.refresh(Refresh.True)
            },
            Map::class.java as Class<Map<String, Any?>>,
        ).block()
    }

    @Test
    fun `should provide event stream query schema`() {
        eventStore.append(generateEventStream(namedAggregate.aggregateId(generateGlobalId()))).block()
        val schema = eventStreamQueryBackend.requiredQueryModelSchemaProvider().schema().block()!!

        schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM)
        schema.fields.assert().containsKey(LogicalField("body.name"))
        schema.fields.getValue(LogicalField("body")).bindings.assert()
            .containsKey(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `public constructor should expose default event stream schema`() {
        eventStore.append(generateEventStream(namedAggregate.aggregateId(generateGlobalId()))).block()
        val queryBackend = ElasticsearchEventStreamQueryBackend(namedAggregate, elasticsearchClient)

        queryBackend.schema().test()
            .assertNext { schema -> schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM) }
            .verifyComplete()
    }

    @Test
    fun `configured constructors should expose event stream schema`() {
        val batchSize = 17
        val keepAlive = Duration.ofSeconds(23)
        eventStore.append(generateEventStream(namedAggregate.aggregateId(generateGlobalId()))).block()
        val queryBackend = ElasticsearchEventStreamQueryBackend(
            namedAggregate,
            elasticsearchClient,
            EventStreamFilterConverter,
            batchSize,
            keepAlive,
        )
        val factoryBackend = ElasticsearchEventStreamQueryBackendFactory(
            elasticsearchClient,
            batchSize,
            keepAlive,
        ).create(namedAggregate) as ElasticsearchEventStreamQueryBackend

        Flux.concat(queryBackend.schema(), factoryBackend.schema())
            .test()
            .assertNext { schema -> schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM) }
            .assertNext { schema -> schema.model.assert().isEqualTo(QueryModel.EVENT_STREAM) }
            .verifyComplete()
    }

    @Test
    fun `custom filter converter should make schema unavailable`() {
        val converter = object : AbstractElasticsearchFilterConverter() {}
        val queryBackend = ElasticsearchEventStreamQueryBackend(
            namedAggregate,
            elasticsearchClient,
            converter,
        )

        queryBackend.schema().test()
            .expectError(QuerySchemaUnavailableException::class.java)
            .verify()
        queryBackend.refresh().test()
            .expectError(QuerySchemaUnavailableException::class.java)
            .verify()
    }

    @Test
    fun `should query event stream by stream id`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(generateGlobalId()))
        eventStore.append(eventStream).block()

        condition { id(eventStream.id) }
            .count(eventStreamQueryBackend)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `limit zero should query every event stream through pit`() {
        val tenantId = generateGlobalId()
        val eventStreams = (1..3).map {
            generateEventStream(namedAggregate.aggregateId(id = generateGlobalId(), tenantId = tenantId))
        }

        Flux.concat(eventStreams.map { eventStore.append(it) })
            .thenMany(
                listQuery {
                    condition { tenantId(tenantId) }
                }.query(eventStreamQueryBackend)
            )
            .test()
            .expectNextCount(3)
            .verifyComplete()
    }

    @Test
    fun `should query null as a missing field`() {
        val eventStream = generateEventStream(
            namedAggregate.aggregateId(id = generateGlobalId(), tenantId = generateGlobalId())
        )
        eventStore.append(eventStream).block()

        condition {
            tenantId(eventStream.aggregateId.tenantId)
            "missingField".isNull()
        }
            .count(eventStreamQueryBackend)
            .test()
            .expectNext(1L)
            .verifyComplete()
        condition {
            tenantId(eventStream.aggregateId.tenantId)
            "missingField".notNull()
        }
            .count(eventStreamQueryBackend)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }

    @Test
    fun `should query keyword field with literal string operators`() {
        val target = generateEventStream(
            namedAggregate.aggregateId(id = generateGlobalId(), tenantId = """Tenant*?Literal\Tail""")
        )
        val wildcardCandidate = generateEventStream(
            namedAggregate.aggregateId(id = generateGlobalId(), tenantId = "TenantXYLiteralZTail")
        )
        eventStore.append(target)
            .then(eventStore.append(wildcardCandidate))
            .block()

        Flux.concat(
            condition { "tenantId".contains("*?literal", ignoreCase = true) }.count(eventStreamQueryBackend),
            condition { "tenantId".startsWith("tenant*?", ignoreCase = true) }.count(eventStreamQueryBackend),
            condition { "tenantId".endsWith("""\tail""", ignoreCase = true) }.count(eventStreamQueryBackend),
        ).test()
            .expectNext(1L, 1L, 1L)
            .verifyComplete()
    }
}

private fun FilterExpression.count(backend: EventStreamQueryBackend) = backend.count(this)

@Suppress("DEPRECATION")
private fun Condition.count(backend: EventStreamQueryBackend) = backend.count(toFilterExpression())

private fun IListQuery.query(backend: EventStreamQueryBackend) = backend.list(this)
