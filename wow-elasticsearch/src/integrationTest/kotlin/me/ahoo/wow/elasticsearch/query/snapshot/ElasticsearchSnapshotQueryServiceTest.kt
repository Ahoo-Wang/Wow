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
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.ElasticsearchMandatoryTenantPolicy
import me.ahoo.wow.elasticsearch.query.legacyElasticsearchQueryGateway
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.query.SnapshotQueryServiceSpec
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Instant

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
        val gateway = legacyElasticsearchQueryGateway(
            elasticsearchClient,
            QueryTarget(MOCK_AGGREGATE_METADATA, QueryDocumentKind.SNAPSHOT),
            MOCK_AGGREGATE_METADATA
        )
        return ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient, gateway)
    }

    override fun createSnapshotStore(): SnapshotStore {
        return ElasticsearchSnapshotStore(elasticsearchClient)
    }

    @Test
    fun `legacy facade preserves dynamic time while mandatory tenant and active policies match direct gateway`() {
        val target = QueryTarget(MOCK_AGGREGATE_METADATA, QueryDocumentKind.SNAPSHOT)
        val policy = ElasticsearchMandatoryTenantPolicy(TenantId.DEFAULT_TENANT_ID)
        val gateway = legacyElasticsearchQueryGateway(
            elasticsearchClient,
            target,
            MOCK_AGGREGATE_METADATA,
            listOf(policy)
        )
        val service = ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient, gateway)
            .create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        saveSnapshot(tenantId = "denied-tenant", deleted = false)
        saveSnapshot(tenantId = TenantId.DEFAULT_TENANT_ID, deleted = true)

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
                legacy.assertLegacyEquivalentTo(direct, SNAPSHOT_TIME_FIELDS)
                legacy["tenantId"].assert().isEqualTo(TenantId.DEFAULT_TENANT_ID)
                legacy["deleted"].assert().isEqualTo(false)
                policy.calls.get().assert().isEqualTo(2)
            }
            .verifyComplete()
    }

    private fun saveSnapshot(tenantId: String, deleted: Boolean) {
        val id = generateGlobalId()
        val stateAggregate = ConstructorStateAggregateFactory.create(
            metadata = MOCK_AGGREGATE_METADATA.state,
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(id, tenantId),
            state = MockStateAggregate(id),
            version = 1,
            firstEventTime = 1_001,
            eventTime = 1_002,
            deleted = deleted
        )
        snapshotStore.save(SimpleSnapshot(stateAggregate, 1_003)).block()
    }

    private fun DynamicDocument.assertLegacyEquivalentTo(
        direct: DynamicDocument,
        timeFields: Set<String>
    ) {
        keys.assert().isEqualTo(direct.keys)
        forEach { (field, value) ->
            if (field in timeFields) {
                value.assert().isInstanceOf(Long::class.javaObjectType)
                value.assert().isEqualTo((direct[field] as Instant).toEpochMilli())
            } else {
                value.assert().isEqualTo(direct[field])
            }
        }
    }

    companion object {
        private val SNAPSHOT_TIME_FIELDS = setOf("firstEventTime", "eventTime", "snapshotTime")
    }
}
