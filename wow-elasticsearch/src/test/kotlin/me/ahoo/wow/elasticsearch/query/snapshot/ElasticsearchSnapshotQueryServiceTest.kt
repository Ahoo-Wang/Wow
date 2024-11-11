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
import me.ahoo.wow.elasticsearch.WowJsonpMapper
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.elasticsearch.client.ClientConfiguration
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test
import java.time.Clock

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
    lateinit var snapshotRepository: SnapshotRepository
    lateinit var snapshot: Snapshot<MockStateAggregate>

    @BeforeEach
    fun init() {
        val clientConfiguration = ClientConfiguration.builder()
            .connectedTo(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.httpHostAddress)
            .usingSsl(ElasticsearchLauncher.ELASTICSEARCH_CONTAINER.createSslContextFromCa())
            .withBasicAuth("elastic", ElasticsearchLauncher.ELASTIC_PWD)
            .build()
        val restClient = ElasticsearchClients.getRestClient(clientConfiguration)
        val transport = RestClientTransport(restClient, WowJsonpMapper)
        val elasticsearchClient = ReactiveElasticsearchClient(transport)
        val factory = ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient = elasticsearchClient
        )
        snapshotQueryService = factory.create(aggregateMetadata)
        snapshotRepository = ElasticsearchSnapshotRepository(
            elasticsearchClient = elasticsearchClient
        )

        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val stateAggregate = ConstructorStateAggregateFactory.create(aggregateMetadata.state, aggregateId).block()!!
        snapshot =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()
    }

    @Test
    fun single() {
        singleQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicSingle() {
        singleQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
            projection {
                include("contextName")
                exclude("firstEventTime")
            }
            sort {
                "version".asc()
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun list() {
        listQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
            limit(10)
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        listQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
            limit(10)
        }.dynamicQuery(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun paged() {
        pagedQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicPaged() {
        pagedQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun count() {
        condition {
            id(snapshot.aggregateId.id)
        }.count(snapshotQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }
}
