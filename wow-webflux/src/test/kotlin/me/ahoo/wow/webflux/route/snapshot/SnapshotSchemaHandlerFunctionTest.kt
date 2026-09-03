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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import me.ahoo.wow.webflux.route.testAggregateRouteContract
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory
import java.util.concurrent.atomic.AtomicInteger

class SnapshotSchemaHandlerFunctionTest {

    @Test
    fun `get should return sorted public metadata without physical bindings`() {
        val provider = RecordingSchemaProvider(SCHEMA)
        val handler = SnapshotSchemaHandlerFunctionFactory(
            snapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory(provider),
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
        body.assert().doesNotContain("resolvedField", "physicalField", "storageType", "projectionField", "rewriteMode")

        provider.schemaCalls.get().assert().isOne()
        provider.refreshCalls.get().assert().isZero()
    }

    @Test
    fun `refresh should call refresh and return public metadata`() {
        val provider = RecordingSchemaProvider(SCHEMA)
        val handler = SnapshotSchemaRefreshHandlerFunctionFactory(
            snapshotQueryBackendFactory = RecordingSnapshotQueryBackendFactory(provider),
            exceptionHandler = WebFluxRequestExceptionHandler(),
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA_REFRESH))

        val body = client(handler).post().uri("/").exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        body.toJsonNode<tools.jackson.databind.JsonNode>()["fields"][0]["field"]
            .stringValue().assert().isEqualTo("state.a")

        provider.schemaCalls.get().assert().isZero()
        provider.refreshCalls.get().assert().isOne()
    }

    @Test
    fun `unavailable provider should return unavailable error`() {
        val backendFactory = RecordingSnapshotQueryBackendFactory(
            UnavailableSchemaProvider,
        )
        val exceptionHandler = WebFluxRequestExceptionHandler()
        val schemaHandler = SnapshotSchemaHandlerFunctionFactory(
            snapshotQueryBackendFactory = backendFactory,
            exceptionHandler = exceptionHandler,
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA))
        val refreshHandler = SnapshotSchemaRefreshHandlerFunctionFactory(
            snapshotQueryBackendFactory = backendFactory,
            exceptionHandler = exceptionHandler,
        ).create(testAggregateRouteContract(BuiltInHttpRouteHandlerKeys.Snapshot.SCHEMA_REFRESH))

        client(schemaHandler).get().uri("/").exchange()
            .expectStatus().isEqualTo(503)
        client(refreshHandler).post().uri("/").exchange()
            .expectStatus().isEqualTo(503)
    }

    private fun client(handler: org.springframework.web.reactive.function.server.HandlerFunction<*>) =
        WebTestClient.bindToRouterFunction(
            RouterFunctions.route()
                .GET("/") { request -> handler.handle(request) }
                .POST("/") { request -> handler.handle(request) }
                .build()
        ).build()

    private class RecordingSnapshotQueryBackendFactory(
        private val schemaProvider: QueryModelSchemaProvider,
    ) : SnapshotQueryBackendFactory {
        override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
            QueryBackendBinding(NoOpSnapshotQueryBackend(namedAggregate), schemaProvider)
    }

    private class RecordingSchemaProvider(
        private val schema: QueryModelSchema,
    ) : QueryModelSchemaProvider {
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

    private object UnavailableSchemaProvider : QueryModelSchemaProvider {
        override fun schema(): Mono<QueryModelSchema> = Mono.error(
            QuerySchemaUnavailableException("Schema unavailable."),
        )

        override fun refresh(): Mono<QueryModelSchema> = schema()
    }

    private companion object {
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
                    resolvedField = QueryField("secret.path"),
                    physicalField = QueryField("secret.path"),
                    storageType = QueryStorageType("keyword"),
                )
            ),
            rewriteMode = QueryRewriteMode.REQUIRED,
        )
        val SCHEMA = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = setOf(QueryCapability.EXACT_MATCH),
            fields = linkedMapOf(
                QueryField("state.z") to FIELD_SCHEMA,
                QueryField("state.a") to FIELD_SCHEMA,
            ),
        )
    }
}
