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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.mongo.query.planned

import com.mongodb.MongoNamespace
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryElementPathMode
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryExecutionProfile
import me.ahoo.wow.query.gateway.QueryExecutionProfiles
import me.ahoo.wow.query.gateway.QueryGatewayRuntime
import me.ahoo.wow.query.gateway.QueryLegacyDialect
import me.ahoo.wow.query.gateway.QueryLegacyDialectResolver
import me.ahoo.wow.query.gateway.QueryMatchScopeMode
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryOperationProfileKey
import me.ahoo.wow.query.gateway.QueryPurpose
import me.ahoo.wow.query.gateway.QueryRawServiceSource
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryValidationMode
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.container.MongoTestFixture
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class MongoEventStreamRecordQueryBackendIntegrationTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("planned_event_query")

    private lateinit var collectionNamespace: MongoNamespace

    @BeforeEach
    fun setup() {
        collectionNamespace = MongoNamespace(mongo.databaseName, "order_event_stream")
        collection().insertMany(
            listOf(
                event("stream-1", "order-1", "tenant-1"),
                event("stream-2", "order-2", "tenant-1"),
                event("stream-3", "order-3", "tenant-2"),
            ),
        ).toMono().block()
    }

    @Test
    fun `gateway planned EventStream should enforce tenant without snapshot deletion`() {
        val contribution = MongoEventStreamQueryBinding.frameworkFields(schema(), collectionNamespace)
            .toContribution(collection())
        contribution.analyticsBackend.assert().isNull()
        contribution.supportedOperations.assert().doesNotContain(QueryOperation.ANALYZE)
        val gateway = QueryGatewayRuntime.create(
            namedAggregates = listOf(target.namedAggregate),
            backendComposition = QueryBackendComposition(
                listOf(contribution),
                mapOf(target to BackendId("mongo")),
            ),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate) =
                    NoOpSnapshotQueryServiceFactory.create<Any>(namedAggregate)

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.FIELD)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.Subject("subject-1", "tenant-1"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = mapOf(
                    QueryOperationProfileKey(target, QueryOperation.COUNT) to planned,
                    QueryOperationProfileKey(target, QueryOperation.PAGE) to planned,
                ),
            ),
        ).gateway
        val call = QueryCall(target, QueryPurpose("event-audit"))

        gateway.count(call, Condition.ALL).block().assert().isEqualTo(2)
        val page = gateway.page(call, PagedQuery(Condition.ALL, pagination = Pagination(1, 1))).block()!!
        page.total.assert().isEqualTo(2)
        page.list.assert().hasSize(1)
        page.list.single()[MessageRecords.ID].assert().isEqualTo("stream-1")
        page.list.single()[MessageRecords.AGGREGATE_ID].assert().isEqualTo("order-1")
    }

    private fun schema(): QueryDocumentSchema = QueryDocumentSchema(
        target,
        listOf(
            textField(QueryFieldId.System(SystemFieldKind.IDENTITY), sortable = true),
            textField(QueryFieldId.System(SystemFieldKind.AGGREGATE_ID)),
            textField(QueryFieldId.System(SystemFieldKind.TENANT_ID)),
        ),
        emptyList(),
    )

    private fun textField(id: QueryFieldId, sortable: Boolean = false) = QueryFieldSchema(
        id,
        LogicalFieldType.Text,
        Presence.REQUIRED,
        Nullability.NON_NULL,
        setOf(PredicateOperator.EQ, PredicateOperator.IN),
        buildSet {
            add(FieldCapability.EXACT)
            add(FieldCapability.PROJECTABLE)
            if (sortable) add(FieldCapability.SORTABLE)
        },
    )

    private fun collection() = mongo.database().getCollection(collectionNamespace.collectionName)

    private fun event(id: String, aggregateId: String, tenantId: String) = Document(
        linkedMapOf(
            Documents.ID_FIELD to id,
            MessageRecords.AGGREGATE_ID to aggregateId,
            MessageRecords.TENANT_ID to tenantId,
            MessageRecords.VERSION to 1,
        ),
    )

    private companion object {
        val target = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.EVENT_STREAM,
        )
        val planned = QueryExecutionProfile(QueryExecutionMode.PLANNED, QueryValidationMode.STRICT)
    }
}
