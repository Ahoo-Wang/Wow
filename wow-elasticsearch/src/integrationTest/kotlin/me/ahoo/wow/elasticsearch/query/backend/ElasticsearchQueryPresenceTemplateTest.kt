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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.ExistsIndexTemplateRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.createElasticsearchTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

internal class ElasticsearchQueryPresenceTemplateTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    @Test
    fun `templates should make new indices ready without migrating existing indices`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val initializer = IndexTemplateInitializer(client.createElasticsearchTemplate())
        val indices = listOf(
            IndexPair(
                template = "wow-snapshot-template",
                old = "wow.${elasticsearch.index("presence_old_snapshot")}.snapshot",
                new = "wow.${elasticsearch.index("presence_new_snapshot")}.snapshot",
            ),
            IndexPair(
                template = "wow-event-stream-template",
                old = "wow.${elasticsearch.index("presence_old_event")}.es",
                new = "wow.${elasticsearch.index("presence_new_event")}.es",
            ),
        )

        val result = Flux.fromIterable(indices.map(IndexPair::template))
            .concatMap { template -> deleteTemplateIfPresent(client, template) }
            .thenMany(Flux.fromIterable(indices).concatMap { pair -> createAndIndex(client, pair.old) })
            .then(initializer.ensureAllTemplates())
            .thenMany(Flux.fromIterable(indices).concatMap { pair -> createAndIndex(client, pair.new) })
            .thenMany(Flux.fromIterable(indices).concatMap { pair -> verifyPair(client, pair) })
            .then()

        result.test().verifyComplete()
    }

    @Test
    fun `legacy query should fail closed when elasticsearch returns a hit without source`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val index = elasticsearch.index("legacy_source_disabled")
        val service = SourceNullQueryService(client, index)

        client.indices().create { request ->
            request.index(index).mappings { mapping -> mapping.source { source -> source.enabled(false) } }
        }.then(
            Mono.defer {
                client.index(
                    IndexRequest.of<Map<String, Any?>> { request ->
                        request.index(index).id("1").document(mapOf("field" to "value")).refresh(Refresh.True)
                    },
                )
            },
        ).then(Mono.defer { service.dynamicPaged(PagedQuery(Condition.ALL)) })
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    private fun deleteTemplateIfPresent(
        client: ReactiveElasticsearchClient,
        template: String,
    ): Mono<Void> = client.indices().existsIndexTemplate(
        ExistsIndexTemplateRequest.of { request -> request.name(template) },
    ).flatMap { exists ->
        if (exists.value()) {
            client.indices().deleteIndexTemplate { request -> request.name(template) }.then()
        } else {
            Mono.empty()
        }
    }

    private fun createAndIndex(
        client: ReactiveElasticsearchClient,
        index: String,
    ): Mono<Void> {
        val source = ElasticsearchQueryPresenceEncoder.encode(
            linkedMapOf(
                "rootValue" to "value",
                "payload" to linkedMapOf(
                    "emptyObject" to emptyMap<String, Any?>(),
                    "emptyList" to emptyList<Any?>(),
                ),
            )
        )
        return Mono.defer { client.indices().create(CreateIndexRequest.of { request -> request.index(index) }) }
            .then(
                Mono.defer {
                    client.index(
                        IndexRequest.of<Map<String, Any?>> { request ->
                            request.index(index).id("1").document(source).refresh(Refresh.True)
                        }
                    )
                }
            ).then()
    }

    private fun verifyPair(
        client: ReactiveElasticsearchClient,
        pair: IndexPair,
    ): Mono<Void> = Mono.zip(
        readiness(client, pair.old),
        readiness(client, pair.new),
        client.indices().getMapping(GetMappingRequest.of { request -> request.index(pair.old) }),
        client.indices().getMapping(GetMappingRequest.of { request -> request.index(pair.new) }),
    ).doOnNext { results ->
        results.t1.assert().isEqualTo(
            QueryBackendReadiness.NotReady(QueryBackendReadinessReason.MAPPING_INCOMPATIBLE)
        )
        results.t2.assert().isEqualTo(QueryBackendReadiness.Ready)
        val oldMapping = checkNotNull(results.t3.mappings()[pair.old]).mappings()
        oldMapping.meta().containsKey(ElasticsearchQueryReadiness.PRESENCE_VERSION_META).assert().isFalse()
        assertPresenceMapping(checkNotNull(results.t4.mappings()[pair.new]).mappings())
    }.then()

    private fun readiness(
        client: ReactiveElasticsearchClient,
        index: String,
    ): Mono<QueryBackendReadiness> = ElasticsearchQueryReadiness(
        client = client,
        index = index,
        requirements = ElasticsearchQueryReadinessRequirements(
            configurationValid = true,
            fields = emptySet(),
            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
            presenceFields = PRESENCE_FIELDS,
        ),
    ).inspect()

    private fun assertPresenceMapping(mapping: TypeMapping) {
        mapping.meta()[ElasticsearchQueryReadiness.PRESENCE_VERSION_META]
            ?.to(Int::class.javaObjectType)
            .assert()
            .isEqualTo(ElasticsearchQueryPresenceEncoder.VERSION)
        mapping.meta()[ElasticsearchQueryReadiness.PRESENCE_TEMPLATE_VERSION_META]
            ?.to(Int::class.javaObjectType)
            .assert()
            .isEqualTo(ElasticsearchQueryPresenceEncoder.VERSION)
        ROOT_PRESENCE_FIELDS.forEach { path ->
            val property = checkNotNull(propertyAt(mapping, path))
            checkNotNull(property).isKeyword.assert().isTrue()
            property.keyword().index().assert().isNotEqualTo(false)
            property.keyword().docValues().assert().isNotEqualTo(false)
        }
        propertyAt(mapping, "payload.__wow_query.null").assert().isNull()
        propertyAt(mapping, "payload.emptyObject.__wow_query.present").assert().isNull()
        propertyAt(mapping, "payload.emptyObject.__wow_query.null").assert().isNull()
        val templates = mapping.dynamicTemplates()
        templates.take(2).map { it.name() }.assert().containsExactly(
            "wow_query_present_keyword",
            "wow_query_null_keyword",
        )
        templates.take(2).zip(listOf("present", "null")).forEach { (named, marker) ->
            named.value().pathMatch().assert().containsExactly(
                "__wow_query.$marker",
                "*.__wow_query.$marker",
            )
            named.value().matchMappingType().assert().containsExactly("string")
            named.value().mapping().isKeyword.assert().isTrue()
        }
    }

    private fun propertyAt(mapping: TypeMapping, path: String): Property? {
        var properties = mapping.properties()
        var current: Property? = null
        path.split('.').forEachIndexed { index, segment ->
            current = properties[segment] ?: return null
            if (index < path.count { it == '.' }) {
                properties = when {
                    checkNotNull(current).isObject -> checkNotNull(current).`object`().properties()
                    checkNotNull(current).isNested -> checkNotNull(current).nested().properties()
                    else -> emptyMap()
                }
            }
        }
        return current
    }

    private data class IndexPair(
        val template: String,
        val old: String,
        val new: String,
    )

    private class SourceNullQueryService(
        override val elasticsearchClient: ReactiveElasticsearchClient,
        override val indexName: String,
    ) : AbstractElasticsearchQueryService<DynamicDocument>() {
        override val namedAggregate: NamedAggregate = MaterializedNamedAggregate("test", "source-null")
        override val conditionConverter: ConditionConverter<Query> = object : ConditionConverter<Query> {
            override fun convert(condition: Condition): Query = Query.of { it.matchAll { match -> match } }
        }

        override fun toTypedResult(document: DynamicDocument): DynamicDocument = document
    }

    private companion object {
        val ROOT_PRESENCE_FIELDS = setOf(
            "__wow_query.present",
            "__wow_query.null",
            "payload.__wow_query.present",
        )
        val PRESENCE_FIELDS = setOf(
            *ROOT_PRESENCE_FIELDS.toTypedArray(),
            "payload.__wow_query.null",
            "payload.emptyObject.__wow_query.present",
            "payload.emptyObject.__wow_query.null",
        )
    }
}
