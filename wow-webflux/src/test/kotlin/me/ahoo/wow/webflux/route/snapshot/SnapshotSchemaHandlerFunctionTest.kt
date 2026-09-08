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
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
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
        json["root"]["properties"]["state"]["properties"].propertyNames().asSequence().toList().assert().containsExactly(
            "a",
            "z"
        )
        json["root"]["properties"]["state"]["properties"]["a"]["capabilities"][0].stringValue().assert().isEqualTo(
            "EXACT_MATCH"
        )
        json["root"]["properties"]["state"]["properties"]["a"]["valueTypes"][0].stringValue().assert().isEqualTo(
            "STRING"
        )
        json["root"]["properties"]["state"]["properties"]["a"]["enumValues"][0].stringValue().assert().isEqualTo("OPEN")
        json["root"]["properties"]["state"]["properties"]["a"]["enumValues"][1].intValue().assert().isEqualTo(2)
        json["root"]["properties"]["state"]["properties"]["a"]["enumValues"][2].booleanValue().assert().isTrue()
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

        body.toJsonNode<tools.jackson.databind.JsonNode>()["root"]["properties"]["state"]["properties"]["a"]["kind"]
            .stringValue().assert().isEqualTo("SCALAR")

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
        val VALUE = QueryValueSchema(
            kind = QueryValueKind.SCALAR,
            enumValues = listOf(
                JsonNodeFactory.instance.stringNode("OPEN"),
                JsonNodeFactory.instance.numberNode(2),
                JsonNodeFactory.instance.booleanNode(true),
            ),
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
            required = true,
        )
        val ROOT = LogicalQuerySchema(
            QueryValueSchema(
                QueryValueKind.OBJECT,
                properties = mapOf(
                    "state" to QueryValueSchema(QueryValueKind.OBJECT, properties = linkedMapOf("z" to VALUE, "a" to VALUE)),
                )
            )
        )
        val SCHEMA = QueryModelSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = setOf(QueryCapability.EXACT_MATCH),
            definition = ROOT,
            bindings = ROOT.values.keys.filter { it.segments.size == 2 }.associateWith { path ->
                val physical = QueryPathTemplate(
                    listOf(QueryPathSegment.Property("secret"), QueryPathSegment.Property("path"))
                )
                QueryValueBindings(
                    mapOf(
                        QueryCapability.EXACT_MATCH to QueryFieldBindingTemplate(physical, setOf(QueryStorageType("keyword")))
                    ),
                    physical,
                    path,
                )
            },
        )
    }
}
