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

import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStore
import me.ahoo.wow.elasticsearch.query.ElasticsearchMandatoryTenantPolicy
import me.ahoo.wow.elasticsearch.query.legacyElasticsearchQueryGateway
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.count
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.query.EventStreamQueryServiceSpec
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test
import reactor.core.publisher.Mono
import java.time.Instant

class ElasticsearchEventStreamQueryServiceTest : EventStreamQueryServiceSpec() {
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

    override fun createEventStreamQueryServiceFactory(): EventStreamQueryServiceFactory {
        val gateway = legacyElasticsearchQueryGateway(
            elasticsearchClient,
            QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM),
            MOCK_AGGREGATE_METADATA
        )
        return ElasticsearchEventStreamQueryServiceFactory(elasticsearchClient, gateway)
    }

    @Test
    fun `should query event stream by stream id`() {
        val eventStream = generateEventStream(namedAggregate.aggregateId(generateGlobalId()))
        eventStore.append(eventStream).block()

        condition { id(eventStream.id) }
            .count(eventStreamQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `legacy facade preserves dynamic time while mandatory tenant policy matches direct gateway`() {
        val target = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)
        val policy = ElasticsearchMandatoryTenantPolicy(TenantId.DEFAULT_TENANT_ID)
        val gateway = legacyElasticsearchQueryGateway(
            elasticsearchClient,
            target,
            MOCK_AGGREGATE_METADATA,
            listOf(policy)
        )
        val service = ElasticsearchEventStreamQueryServiceFactory(elasticsearchClient, gateway).create(namedAggregate)
        val allowed = generateEventStream(namedAggregate.aggregateId(generateGlobalId(), TenantId.DEFAULT_TENANT_ID))
        val denied = generateEventStream(namedAggregate.aggregateId(generateGlobalId(), "denied-tenant"))
        eventStore.append(allowed).then(eventStore.append(denied)).block()

        Mono.zip(
            service.dynamicList(ListQuery(Condition.ALL)).collectList(),
            gateway.list(
                ListQueryRequest(
                    target = target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Typed(DynamicDocument::class.java),
                    limit = 10
                )
            ).collectList()
        ).test()
            .assertNext { results ->
                val legacy = results.t1.single()
                val direct = results.t2.single()
                legacy.keys.assert().isEqualTo(direct.keys)
                legacy.forEach { (field, value) ->
                    if (field == "createTime") {
                        value.assert().isInstanceOf(Long::class.javaObjectType)
                        value.assert().isEqualTo((direct[field] as Instant).toEpochMilli())
                    } else {
                        value.assert().isEqualTo(direct[field])
                    }
                }
                legacy["tenantId"].assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
                legacy["id"].assert().isEqualTo(allowed.id)
                policy.calls.get().assert().isEqualTo(2)
            }
            .verifyComplete()
    }

}
