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

package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteTestFixtures
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.atomic.AtomicInteger

class SnapshotSchemaHandlerFunctionTest {

    @Test
    fun `get should return sorted public metadata without physical bindings`() {
        val backend = RecordingSchemaBackend(SCHEMA)
        val handler = SnapshotSchemaHandlerFunctionFactory(
            snapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory(backend),
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA))

        val body = client(handler).get().uri("/").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val json = body.toJsonNode<tools.jackson.databind.JsonNode>()

        json["model"].stringValue().assert().isEqualTo("SNAPSHOT")
        json["capabilities"][0].stringValue().assert().isEqualTo("EXACT_MATCH")
        json["fields"][0]["field"].stringValue().assert().isEqualTo("state.a")
        json["fields"][1]["field"].stringValue().assert().isEqualTo("state.z")
        json["fields"][0]["capabilities"][0].stringValue().assert().isEqualTo("EXACT_MATCH")
        json["fields"][0]["valueTypes"][0].stringValue().assert().isEqualTo("STRING")
        json["fields"][0]["enumValues"][0].stringValue().assert().isEqualTo("OPEN")
        json["fields"][0]["enumValues"][1].intValue().assert().isEqualTo(2)
        json["fields"][0]["enumValues"][2].booleanValue().assert().isTrue()
        body.assert().doesNotContain("physicalPath", "storageType", "projectionPath")

        backend.schemaCalls.get().assert().isOne()
        backend.refreshCalls.get().assert().isZero()
    }

    @Test
    fun `refresh should call refresh and return public metadata`() {
        val backend = RecordingSchemaBackend(SCHEMA)
        val handler = SnapshotSchemaRefreshHandlerFunctionFactory(
            snapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory(backend),
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA_REFRESH))

        val body = client(handler).post().uri("/").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        body.toJsonNode<tools.jackson.databind.JsonNode>()["fields"][0]["field"]
            .stringValue().assert().isEqualTo("state.a")

        backend.schemaCalls.get().assert().isZero()
        backend.refreshCalls.get().assert().isOne()
    }

    @Test
    fun `backend without schema provider should return unavailable error`() {
        assertThrows<QuerySchemaUnavailableException> {
            SnapshotSchemaHandlerFunctionFactory(
                snapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory(
                    NoOpSnapshotQueryBackend(NAMED_AGGREGATE),
                ),
                exceptionHandler = WebFluxRequestExceptionHandler(),
            ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA))
        }
    }

    private fun client(handler: org.springframework.web.reactive.function.server.HandlerFunction<*>) =
        WebTestClient.bindToRouterFunction(
            RouterFunctions.route()
                .GET("/") { request -> handler.handle(request) }
                .POST("/") { request -> handler.handle(request) }
                .build()
        ).build()

    private class RecordingSnapshotQueryBackendFactory(
        private val backend: SnapshotQueryBackend,
    ) : SnapshotQueryBackendFactory {
        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryBackend = backend
    }

    private class RecordingSchemaBackend(
        private val schema: QueryModelSchema,
    ) : SnapshotQueryBackend by NoOpSnapshotQueryBackend(NAMED_AGGREGATE), QueryModelSchemaProvider {
        val schemaCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()

        override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier {
            schemaCalls.incrementAndGet()
            schema
        }

        override fun refresh(): Mono<QueryModelSchema> = Mono.fromSupplier {
            refreshCalls.incrementAndGet()
            schema
        }
    }

    private companion object {
        val NAMED_AGGREGATE = RouteTestFixtures.MOCK_AGGREGATE_ROUTE_METADATA.aggregateMetadata.materialize()
        val FIELD_SCHEMA = QueryFieldSchema(
            title = null,
            description = null,
            enumValues = listOf(
                JsonNodeFactory.instance.stringNode("OPEN"),
                JsonNodeFactory.instance.numberNode(2),
                JsonNodeFactory.instance.booleanNode(true),
            ),
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            bindings = mapOf(
                QueryCapability.EXACT_MATCH to QueryFieldBinding(
                    physicalPath = "secret.path",
                    storageType = QueryStorageType("keyword"),
                )
            ),
        )
        val SCHEMA = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = setOf(QueryCapability.EXACT_MATCH),
            fields = linkedMapOf(
                LogicalField("state.z") to FIELD_SCHEMA,
                LogicalField("state.a") to FIELD_SCHEMA,
            ),
        )
    }
}
