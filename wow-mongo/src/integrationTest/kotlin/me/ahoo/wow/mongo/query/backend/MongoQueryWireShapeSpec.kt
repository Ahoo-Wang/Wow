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

package me.ahoo.wow.mongo.query.backend

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.abac.EMPTY_ABAC_TAGS
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.toDocument
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySystemFields
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant

class MongoQueryWireShapeSpec {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_query_wire_shape")

    @Test
    fun `snapshot queries use serializer wire encoding for application and system time`() {
        val target = QueryTarget(NAMED_AGGREGATE, QueryDocumentKind.SNAPSHOT)
        val occurredAt = Instant.parse("2026-01-15T12:30:00Z")
        val eventTime = Instant.parse("2026-01-16T00:00:00Z")
        val aggregate = WireAggregate(
            aggregateId = NAMED_AGGREGATE.aggregateId("snapshot-wire"),
            state = WireState(occurredAt),
            firstEventTime = eventTime.minusSeconds(60).toEpochMilli(),
            eventTime = eventTime.toEpochMilli()
        )
        val snapshot = SimpleSnapshot(aggregate, snapshotTime = eventTime.plusSeconds(60).toEpochMilli())
        val document = snapshot.toDocument()
        val state = document["state"] as Map<*, *>
        state["occurredAt"].assert().isEqualTo(occurredAt.toString())
        document["eventTime"].assert().isEqualTo(eventTime.toEpochMilli())
        createCollection(target, document)
        val schema = QuerySchema(
            target,
            QuerySystemFields.fields(target.documentKind) + QueryFieldSchema(
                path = OCCURRED_AT,
                valueKind = QueryFieldValueKind.TIME,
                nullable = false
            )
        )
        val gateway = MongoQueryGatewayHarness(target, schema, MongoQueryBackendFactory(mongo.database())).gateway

        verifySingleId(gateway, target, predicate(OCCURRED_AT, PortableOperator.EQ, occurredAt), "snapshot-wire")
        verifySingleId(
            gateway,
            target,
            PredicateExpression(
                OCCURRED_AT,
                PortableOperator.BETWEEN,
                listOf(QueryValue.InstantValue(occurredAt.minusSeconds(1)), QueryValue.InstantValue(occurredAt.plusSeconds(1)))
            ),
            "snapshot-wire"
        )
        verifySingleId(gateway, target, predicate(LogicalField("eventTime"), PortableOperator.EQ, eventTime), "snapshot-wire")
    }

    @Test
    fun `event stream queries use serializer epoch millis for system time`() {
        val target = QueryTarget(NAMED_AGGREGATE, QueryDocumentKind.EVENT_STREAM)
        val aggregateId = NAMED_AGGREGATE.aggregateId("event-wire")
        val createTime = Instant.parse("2026-02-01T00:00:00Z")
        val stream = MockAggregateCreated("wire").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = 0,
            createTime = createTime.toEpochMilli()
        )
        val document = stream.toDocument()
        document["createTime"].assert().isEqualTo(createTime.toEpochMilli())
        createCollection(target, document)
        val schema = QuerySchema(target, QuerySystemFields.fields(target.documentKind))
        val gateway = MongoQueryGatewayHarness(target, schema, MongoQueryBackendFactory(mongo.database())).gateway

        verifySingleId(
            gateway,
            target,
            PredicateExpression(
                LogicalField("createTime"),
                PortableOperator.BETWEEN,
                listOf(
                    QueryValue.InstantValue(createTime.minusSeconds(1)),
                    QueryValue.InstantValue(createTime.plusSeconds(1))
                )
            ),
            stream.id
        )
    }

    @Test
    fun `snapshot nested array projection preserves dynamic and typed structure`() {
        val target = QueryTarget(NAMED_AGGREGATE, QueryDocumentKind.SNAPSHOT)
        val aggregate = WireAggregate(
            aggregateId = NAMED_AGGREGATE.aggregateId("snapshot-nested"),
            state = WireState(
                Instant.parse("2026-03-01T00:00:00Z"),
                listOf(WireItem("A"), WireItem("B"))
            ),
            firstEventTime = 1,
            eventTime = 2
        )
        createCollection(target, SimpleSnapshot(aggregate, snapshotTime = 3).toDocument())
        val schema = nestedSnapshotSchema(target)
        val gateway = MongoQueryGatewayHarness(
            target,
            schema,
            MongoQueryBackendFactory(mongo.database()),
            fieldAccess = QueryFieldAccess.Restricted(
                setOf(LogicalField("aggregateId"), LogicalField("deleted"), ITEM_SKU)
            )
        ).gateway

        StepVerifier.create(
            gateway.list(
                ListQueryRequest(
                    target = target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Dynamic,
                    limit = 0
                )
            )
        ).assertNext { result -> result[ITEM_SKU.value].assert().isEqualTo(listOf("A", "B")) }
            .verifyComplete()

        StepVerifier.create(
            gateway.list(
                ListQueryRequest(
                    target = target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Typed(
                        NestedSnapshotResult::class.java,
                        QueryProjection.Include(setOf(LogicalField("aggregateId"), ITEM_SKU))
                    ),
                    limit = 0
                )
            )
        ).assertNext { result ->
            result.aggregateId.assert().isEqualTo("snapshot-nested")
            result.state.items.map(ProjectedItem::sku).assert().isEqualTo(listOf("A", "B"))
        }.verifyComplete()
    }

    @Test
    fun `event body collection projection preserves dynamic and typed structure`() {
        val target = QueryTarget(NAMED_AGGREGATE, QueryDocumentKind.EVENT_STREAM)
        val stream = MockAggregateCreated("nested").toDomainEventStream(
            upstream = GivenInitializationCommand(NAMED_AGGREGATE.aggregateId("event-nested")),
            aggregateVersion = 0,
            createTime = 1
        )
        createCollection(target, stream.toDocument())
        val schema = QuerySchema(target, QuerySystemFields.fields(target.documentKind))
        val gateway = MongoQueryGatewayHarness(
            target,
            schema,
            MongoQueryBackendFactory(mongo.database()),
            fieldAccess = QueryFieldAccess.Restricted(setOf(LogicalField("id"), EVENT_BODY_ID))
        ).gateway
        val expectedEventId = stream.body.single().id

        StepVerifier.create(
            gateway.list(
                ListQueryRequest(
                    target = target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Dynamic,
                    limit = 0
                )
            )
        ).assertNext { result -> result[EVENT_BODY_ID.value].assert().isEqualTo(listOf(expectedEventId)) }
            .verifyComplete()

        StepVerifier.create(
            gateway.list(
                ListQueryRequest(
                    target = target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Typed(
                        NestedEventResult::class.java,
                        QueryProjection.Include(setOf(LogicalField("id"), EVENT_BODY_ID))
                    ),
                    limit = 0
                )
            )
        ).assertNext { result ->
            result.id.assert().isEqualTo(stream.id)
            result.body.map(ProjectedEvent::id).assert().isEqualTo(listOf(expectedEventId))
        }.verifyComplete()
    }

    @Test
    fun `malformed nested projection fails stably and cancels the real driver`() {
        val target = QueryTarget(NAMED_AGGREGATE, QueryDocumentKind.SNAPSHOT)
        fun document(id: String, sku: String): Document = SimpleSnapshot(
            WireAggregate(
                aggregateId = NAMED_AGGREGATE.aggregateId(id),
                state = WireState(Instant.parse("2026-03-01T00:00:00Z"), listOf(WireItem(sku))),
                firstEventTime = 1,
                eventTime = 2
            ),
            snapshotTime = 3
        ).toDocument()
        val malformed = document("b-bad", "B").also { snapshot ->
            @Suppress("UNCHECKED_CAST")
            val state = snapshot["state"] as MutableMap<String, Any?>
            state["items"] = "not-an-array"
        }
        createCollection(target, listOf(document("a-good", "A"), malformed))
        val schema = nestedSnapshotSchema(target)
        val factory = MongoObservableQueryBackendFactory(mongo.database())
        factory.verifyRouteReadiness(QueryBackendResolutionContext(target, schema, MatchAll))
        factory.reset()
        val gateway = MongoQueryGatewayHarness(
            target,
            schema,
            factory,
            fieldAccess = QueryFieldAccess.Restricted(
                setOf(LogicalField("aggregateId"), LogicalField("deleted"), ITEM_SKU)
            )
        ).gateway

        StepVerifier.create(
            gateway.list(
                ListQueryRequest(
                    target = target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Typed(
                        NestedSnapshotResult::class.java,
                        QueryProjection.Include(setOf(LogicalField("aggregateId"), ITEM_SKU))
                    ),
                    sort = listOf(QuerySort(LogicalField("aggregateId"), QuerySortDirection.ASC)),
                    limit = 0
                )
            )
        ).assertNext { result -> result.aggregateId.assert().isEqualTo("a-good") }
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
                error.stage.assert().isEqualTo(QueryStage.EXECUTION)
                error.reason.assert().isEqualTo(QueryErrorReason.INCOMPLETE_STREAM)
                error.causeCode.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
            }.verify()

        factory.subscriptionCount.assert().isOne()
        factory.cancellationCount.assert().isOne()
    }

    private fun createCollection(target: QueryTarget, document: Document) {
        createCollection(target, listOf(document))
    }

    private fun createCollection(target: QueryTarget, documents: List<Document>) {
        val collectionName = when (target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> target.namedAggregate.toSnapshotCollectionName()
            QueryDocumentKind.EVENT_STREAM -> target.namedAggregate.toEventStreamCollectionName()
        }
        StepVerifier.create(
            Mono.from(mongo.database().createCollection(collectionName))
                .then(Mono.from(mongo.database().getCollection(collectionName).insertMany(documents)))
        ).expectNextCount(1).verifyComplete()
    }

    private fun nestedSnapshotSchema(target: QueryTarget): QuerySchema = QuerySchema(
        target,
        QuerySystemFields.fields(target.documentKind) + listOf(
            QueryFieldSchema(OCCURRED_AT, QueryFieldValueKind.TIME, nullable = false),
            QueryFieldSchema(
                path = ITEMS,
                valueKind = QueryFieldValueKind.OBJECT,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT
            ),
            QueryFieldSchema.string(ITEM_SKU, nullable = false)
        )
    )

    private fun verifySingleId(
        gateway: me.ahoo.wow.query.QueryGateway,
        target: QueryTarget,
        expression: me.ahoo.wow.api.query.expression.QueryExpression,
        expectedId: String
    ) {
        val identity = when (target.documentKind) {
            QueryDocumentKind.SNAPSHOT -> LogicalField("aggregateId")
            QueryDocumentKind.EVENT_STREAM -> LogicalField("id")
        }
        val results = gateway.list(
            ListQueryRequest(
                target = target,
                expression = expression,
                resultShape = QueryResultShape.Typed(
                    WireIdentity::class.java,
                    QueryProjection.Include(setOf(identity))
                ),
                limit = 0
            )
        )
        StepVerifier.create(results)
            .assertNext { result -> result.identity(target.documentKind).assert().isEqualTo(expectedId) }
            .verifyComplete()
    }

    private fun predicate(field: LogicalField, operator: PortableOperator, value: Instant) =
        PredicateExpression(field, operator, listOf(QueryValue.InstantValue(value)))

    data class WireIdentity(
        val aggregateId: String? = null,
        val id: String? = null
    ) {
        fun identity(documentKind: QueryDocumentKind): String? = when (documentKind) {
            QueryDocumentKind.SNAPSHOT -> aggregateId
            QueryDocumentKind.EVENT_STREAM -> id
        }
    }

    data class WireState(
        val occurredAt: Instant,
        val items: List<WireItem> = emptyList()
    )

    data class WireItem(val sku: String)

    data class NestedSnapshotResult(val aggregateId: String, val state: ProjectedState)

    data class ProjectedState(val items: List<ProjectedItem>)

    data class ProjectedItem(val sku: String)

    data class NestedEventResult(val id: String, val body: List<ProjectedEvent>)

    data class ProjectedEvent(val id: String)

    private class WireAggregate(
        override val aggregateId: AggregateId,
        override val state: WireState,
        override val firstEventTime: Long,
        override val eventTime: Long
    ) : ReadOnlyStateAggregate<WireState> {
        override val ownerId: String = "owner"
        override val spaceId: String = "space"
        override val version: Int = 1
        override val firstOperator: String = "operator"
        override val operator: String = "operator"
        override val eventId: String = "event"
        override val tags: AbacTags = EMPTY_ABAC_TAGS
        override val deleted: Boolean = false
    }

    private companion object {
        val NAMED_AGGREGATE = MaterializedNamedAggregate("mongo-query-wire", "document")
        val OCCURRED_AT = LogicalField("state.occurredAt")
        val ITEMS = LogicalField("state.items")
        val ITEM_SKU = LogicalField("state.items.sku")
        val EVENT_BODY_ID = LogicalField("body.id")
    }
}
