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

package me.ahoo.wow.mongo.query

import com.mongodb.client.model.Projections
import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyTo
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryBackend
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryBackend
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.requireAccepted
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.reactivestreams.Subscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class MongoQueryProjectionResultTest {
    private val collection = mockk<MongoCollection<Document>>()
    private val namedAggregate = MaterializedNamedAggregate("test", "projection")

    @ParameterizedTest
    @CsvSource(
        "SNAPSHOT,aggregateId,single",
        "SNAPSHOT,aggregateId,list",
        "SNAPSHOT,aggregateId,paged",
        "EVENT_STREAM,id,single",
        "EVENT_STREAM,id,list",
        "EVENT_STREAM,id,paged",
    )
    fun `query should allow projected identity to be absent`(
        modelName: String,
        logicalId: String,
        operation: String,
    ) {
        val model = QueryModel(modelName)
        val backend = backend(model)
        val schema = schema(model, logicalId)
        val projection = Projection(exclude = listOf(QueryField(logicalId)))
        val capturedProjection = arrange { Document("value", "visible") }
        val result = when (operation) {
            "single" -> backend.single(
                ResolvedQuery(
                    schema.resolve(SingleQuery(MatchAllFilter, projection))
                        .requireAccepted(QuerySchemaValidationMode.STRICT),
                    schema,
                ),
            ).map(::listOf)

            "list" -> backend.list(
                ResolvedQuery(
                    schema.resolve(ListQuery(MatchAllFilter, projection, limit = 1))
                        .requireAccepted(QuerySchemaValidationMode.STRICT),
                    schema,
                ),
            ).collectList()

            "paged" -> backend.paged(
                ResolvedQuery(
                    schema.resolve(PagedQuery(MatchAllFilter, projection, pagination = Pagination(size = 1)))
                        .requireAccepted(QuerySchemaValidationMode.STRICT),
                    schema,
                ),
            ).map { page ->
                page.total.assert().isEqualTo(7L)
                page.list
            }

            else -> error(operation)
        }

        result.test().assertNext { nodes ->
            val node = nodes.single()
            node.path("value").asString().assert().isEqualTo("visible")
            node.has(logicalId).assert().isFalse()
            node.has("_id").assert().isFalse()
        }.verifyComplete()
        capturedProjection.captured.toBsonDocument().assert()
            .isEqualTo(Projections.exclude("_id").toBsonDocument())
    }

    @ParameterizedTest
    @CsvSource("SNAPSHOT,aggregateId", "EVENT_STREAM,id")
    fun `query identity mapping should preserve strict values and existing fields`(
        modelName: String,
        logicalId: String,
    ) {
        val model = QueryModel(modelName)

        single(model, logicalId) {
            Document("_id", "storage-id").append(logicalId, "old-id").append("value", "visible")
        }.test().assertNext { node ->
            node.path(logicalId).asString().assert().isEqualTo("storage-id")
            node.has("_id").assert().isFalse()
        }.verifyComplete()
        single(model, logicalId) {
            Document(logicalId, "existing-id").append("value", "visible")
        }.test().assertNext { node ->
            node.path(logicalId).asString().assert().isEqualTo("existing-id")
            node.has("_id").assert().isFalse()
        }.verifyComplete()
        single(model, logicalId) { Document("_id", null) }
            .test().verifyError(IllegalStateException::class.java)
        single(model, logicalId) { Document("_id", 42) }
            .test().verifyError(ClassCastException::class.java)
        assertThrows<IllegalStateException> {
            Document("value", "visible").replacePrimaryKeyTo(logicalId)
        }
    }

    @ParameterizedTest
    @CsvSource("SNAPSHOT,aggregateId", "EVENT_STREAM,id")
    fun `each subscription should receive an independent query result`(modelName: String, logicalId: String) {
        val model = QueryModel(modelName)
        val result = single(model, logicalId) {
            Document("_id", "storage-id").append("value", "visible")
        }

        val first = result.block()!!
        first.put("value", "changed")
        val second = result.block()!!

        second.assert().isNotSameAs(first)
        second.path(logicalId).asString().assert().isEqualTo("storage-id")
        second.path("value").asString().assert().isEqualTo("visible")
    }

    private fun backend(model: QueryModel): QueryBackend = if (model == QueryModel.SNAPSHOT) {
        MongoSnapshotQueryBackend(namedAggregate, collection)
    } else {
        MongoEventStreamQueryBackend(namedAggregate, collection)
    }

    private fun schema(model: QueryModel, logicalId: String): QueryModelSchema {
        val logical = QueryField(logicalId)
        val physical = QueryField("_id")
        return QueryModelSchema(
            model,
            emptySet(),
            mapOf(
                logical to QueryFieldSchema(
                    title = null,
                    description = null,
                    enumValues = null,
                    valueTypes = setOf(QueryValueType.STRING),
                    nullable = false,
                    required = true,
                    cardinality = QueryCardinality.SINGLE,
                    semanticType = null,
                    dynamicChildren = false,
                    bindings = mapOf(QueryCapability.PRESENCE to QueryFieldBinding(logical, physical, null)),
                    projectionField = physical,
                    rewriteMode = QueryRewriteMode.NONE,
                ),
            ),
        )
    }

    private fun single(model: QueryModel, logicalId: String, document: () -> Document): Mono<ObjectNode> {
        arrange(document)
        return backend(model).single(ResolvedQuery(SingleQuery(MatchAllFilter), schema(model, logicalId)))
    }

    private fun arrange(document: () -> Document): io.mockk.CapturingSlot<Bson> {
        val projection = slot<Bson>()
        val publisher = mockk<FindPublisher<Document>>()
        every { collection.find(any<Bson>()) } returns publisher
        every { collection.countDocuments(any<Bson>()) } returns Mono.just(7L)
        every { publisher.projection(capture(projection)) } returns publisher
        every { publisher.sort(any()) } returns publisher
        every { publisher.skip(any()) } returns publisher
        every { publisher.limit(any()) } returns publisher
        every { publisher.batchSize(any()) } returns publisher
        every { publisher.first() } returns publisher
        every { publisher.subscribe(any()) } answers {
            Flux.just(document()).subscribe(firstArg<Subscriber<in Document>>())
        }
        return projection
    }
}
