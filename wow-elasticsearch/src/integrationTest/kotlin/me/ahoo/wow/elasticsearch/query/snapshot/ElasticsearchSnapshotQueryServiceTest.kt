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

package me.ahoo.wow.elasticsearch.query.snapshot

import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch.indices.PutMappingRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.DEFAULT_PIT_KEEP_ALIVE
import me.ahoo.wow.elasticsearch.query.DEFAULT_SEARCH_BATCH_SIZE
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolver
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test

class ElasticsearchSnapshotQueryServiceTest : SnapshotQueryServiceSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    lateinit var elasticsearchClient: ReactiveElasticsearchClient

    @BeforeEach
    override fun setup() {
        elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        elasticsearchClient.initSnapshotTemplate()
        super.setup()
    }

    override fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory {
        return ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
    }

    override fun createSnapshotStore(): SnapshotStore {
        return ElasticsearchSnapshotStore(elasticsearchClient)
    }

    @Test
    fun `active refresh should expose mapped alias and runtime capabilities to new queries`() {
        val indexName = MOCK_AGGREGATE_METADATA.toSnapshotIndexName()
        val mappingResolver = ElasticsearchIndexMappingResolver(elasticsearchClient)
        val initial = mappingResolver.currentOrLoad(indexName).block()!!
        initial.resolve("state.data", ElasticsearchFieldUsage.SEARCH).assert().isEqualTo("state.data")
        initial.resolve("state.data", ElasticsearchFieldUsage.EXACT).assert().isEqualTo("state.data.keyword")

        elasticsearchClient.indices().putMapping(
            PutMappingRequest.of { request ->
                request.index(indexName)
                    .properties("aggregateIdAlias") { field ->
                        field.alias { alias -> alias.path("aggregateId") }
                    }
                    .properties("state") { state ->
                        state.`object` { objectField ->
                            objectField
                                .properties("keywordOnly") { it.keyword { keyword -> keyword } }
                                .properties("textOnly") { it.text { text -> text } }
                        }
                    }.runtime("state.runtimeCode") { runtime ->
                        runtime.type(RuntimeFieldType.Keyword)
                            .script { script ->
                                script.source { source -> source.scriptString("emit('runtime')") }
                            }
                    }
            },
        ).block()

        val refreshed = mappingResolver.refresh(indexName).block()!!
        refreshed.changed.assert().isTrue()
        refreshed.mapping.resolve("state.keywordOnly", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("state.keywordOnly")
        refreshed.mapping.resolve("state.textOnly", ElasticsearchFieldUsage.SEARCH)
            .assert().isEqualTo("state.textOnly")
        refreshed.mapping.resolve("aggregateIdAlias", ElasticsearchFieldUsage.EXACT)
            .assert().isEqualTo("aggregateIdAlias")
        refreshed.mapping.resolve("state.runtimeCode", ElasticsearchFieldUsage.SORT)
            .assert().isEqualTo("state.runtimeCode")

        val queryService = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient,
            DEFAULT_SEARCH_BATCH_SIZE,
            DEFAULT_PIT_KEEP_ALIVE,
            mappingResolver,
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        queryService.dynamicList(
            ListQuery(
                condition = Condition.and(
                    Condition.eq("state.keywordOnly", "exact"),
                    Condition.match("state.textOnly", "search"),
                ),
                limit = 10,
            ),
        ).test()
            .verifyComplete()

        queryService.dynamicList(
            ListQuery(
                condition = Condition.and(
                    Condition.eq("aggregateIdAlias", snapshot.aggregateId.id),
                    Condition.eq("state.runtimeCode", "runtime"),
                ),
                sort = listOf(Sort("state.runtimeCode", Sort.Direction.ASC)),
                limit = 10,
            ),
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
