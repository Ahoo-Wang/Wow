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

import co.elastic.clients.transport.rest_client.RestClientTransport
import me.ahoo.wow.elasticsearch.SnapshotJsonpMapper
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.count
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.container.ElasticsearchLauncher
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test

class ElasticsearchSnapshotQueryServiceTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun waitLauncher() {
            ElasticsearchLauncher.isRunning
        }
    }

    private val aggregateMetadata = MOCK_AGGREGATE_METADATA
    lateinit var snapshotQueryService: SnapshotQueryService<MockStateAggregate>

    @BeforeEach
    fun init() {
        snapshotQueryService = createSnapshotQueryService()
    }

    fun createSnapshotQueryService(): SnapshotQueryService<MockStateAggregate> {
        val clientConfiguration = ClientConfiguration.builder()
            .connectedTo(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.httpHostAddress)
            .usingSsl(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.createSslContextFromCa())
            .withBasicAuth("elastic", ElasticsearchLauncher.ELASTIC_PWD)
            .build()
        val restClient = ElasticsearchClients.getRestClient(clientConfiguration)
        val transport = RestClientTransport(restClient, SnapshotJsonpMapper)
        val elasticsearchClient = ReactiveElasticsearchClient(transport)
        val factory = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient = elasticsearchClient
        )
        return factory.create(aggregateMetadata)
    }

    @Test
    fun single() {
        singleQuery {
            condition {
                tenantId(generateGlobalId())
            }
        }.query(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun dynamicSingle() {
        singleQuery {
            condition {
                tenantId(generateGlobalId())
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun query() {
        listQuery {
            condition {
                tenantId(generateGlobalId())
            }
        }.query(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        listQuery {
            condition {
                tenantId(generateGlobalId())
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun paged() {
        pagedQuery {
            condition {
                tenantId(generateGlobalId())
            }
        }.query(snapshotQueryService)
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0L))
            }
            .verifyComplete()
    }

    @Test
    fun dynamicPaged() {
        pagedQuery {
            condition {
                tenantId(generateGlobalId())
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0L))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        condition {
            tenantId(generateGlobalId())
        }.count(snapshotQueryService)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
