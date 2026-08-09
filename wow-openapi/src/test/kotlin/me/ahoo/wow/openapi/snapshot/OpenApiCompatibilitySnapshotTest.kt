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

package me.ahoo.wow.openapi.snapshot

import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.parameters.Parameter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.assertContractSnapshot
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.assertOpenApiSnapshot
import me.ahoo.wow.openapi.snapshot.OpenApiSnapshotSupport.resourcePath
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

internal class OpenApiCompatibilitySnapshotTest {
    private val mapper = ObjectMapperFactory.createJson31()
    private val currentContext = MaterializedNamedBoundedContext("example-service")

    @Test
    fun `generated openapi should match example domain compatibility snapshot`() {
        val openAPI = OpenAPI()
        RouterSpecs(currentContext).build().mergeOpenAPIFromCatalog(openAPI)

        assertOpenApiSnapshot(
            openAPI = openAPI,
            snapshotPath = resourcePath("openapi/example-domain-openapi.snapshot.json")
        )
    }

    @Test
    fun `generated BI script request schema should retain its OpenAPI 3 point 1 types`() {
        val openAPI = OpenAPI()
        RouterSpecs(currentContext).build().mergeOpenAPIFromCatalog(openAPI)

        val document = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(openAPI)
        document.path("openapi").asText().assert().isEqualTo("3.1.0")
        val schemas = document.path("components").path("schemas")
        val request = schemas.path("wow.openapi.BiScriptRequest")
        val topology = schemas.path("wow.openapi.BiScriptTopologyRequest")
        val cluster = schemas.path("wow.openapi.BiScriptClusterRequest")
        val topologyMode = schemas.path("wow.openapi.BiScriptTopologyMode")
        val unsupportedTypeStrategy = schemas.path("wow.openapi.BiScriptUnsupportedTypeStrategy")

        request.path("type").asText().assert().isEqualTo("object")
        topology.path("type").asText().assert().isEqualTo("object")
        cluster.path("type").asText().assert().isEqualTo("object")

        listOf(
            "database",
            "consumerDatabase",
            "timezone",
            "kafkaBootstrapServers",
            "topicPrefix",
        ).forEach { propertyName ->
            val alternatives = request.path("properties").path(propertyName).path("anyOf")
            alternatives.any { it.path("type").asText() == "string" }.assert().isTrue()
            alternatives.any { it.path("type").asText() == "null" }.assert().isTrue()
        }

        val maxExpansionDepth = request.path("properties").path("maxExpansionDepth").path("anyOf")
        val integerAlternative = maxExpansionDepth.first { it.path("type").asText() == "integer" }
        integerAlternative.path("format").asText().assert().isEqualTo("int32")
        maxExpansionDepth.any { it.path("type").asText() == "null" }.assert().isTrue()

        listOf("topology", "unsupportedTypeStrategy").forEach { propertyName ->
            val alternatives = request.path("properties").path(propertyName).path("anyOf")
            alternatives.any { it.path("type").asText() == "null" }.assert().isTrue()
            alternatives.any { it.has("\$ref") }.assert().isTrue()
        }

        request.path("required").isMissingNode.assert().isTrue()
        topology.path("required").map { it.asText() }.assert().contains("mode")
        topologyMode.path("type").asText().assert().isEqualTo("string")
        topologyMode.path("enum").map { it.asText() }.assert()
            .containsExactly("CLUSTER", "STANDALONE")
        unsupportedTypeStrategy.path("type").asText().assert().isEqualTo("string")
        unsupportedTypeStrategy.path("enum").map { it.asText() }.assert()
            .containsExactly("FAIL", "RAW_JSON")
    }

    @Test
    fun `generated analytics contract should expose an opaque scalar cursor and complete route`() {
        val openAPI = OpenAPI()
        RouterSpecs(currentContext).build().mergeOpenAPIFromCatalog(openAPI)

        val document = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(openAPI)
        val schemas = document.path("components").path("schemas")
        val cursor = schemas.path("wow.api.query.AnalyticsCursor")
        cursor.path("type").asText().assert().isEqualTo("string")
        cursor.path("maxLength").asInt().assert().isEqualTo(256)
        cursor.path("pattern").asText().assert().isEqualTo("^[A-Za-z0-9._-]+$")

        val request = document.path("components").path("requestBodies").path("wow.AnalyticsQuery")
            .path("content").path("application/json").path("schema")
        val variants = request.path("oneOf")
        variants.assert().hasSize(2)
        variants.forEach { variant ->
            variant.path("additionalProperties").asBoolean().assert().isFalse()
            variant.path("required").map { it.asText() }.assert().contains("grouping", "metrics", "window")
            variant.path("properties").path("metrics").path("minItems").asInt().assert().isEqualTo(1)
            variant.path("properties").path("metrics").path("items").path("oneOf").assert().hasSize(2)
        }
        val global = variants.first()
        global.path("properties").path("grouping").path("properties").path("kind").path("const")
            .asText().assert().isEqualTo("GLOBAL")
        global.path("properties").path("grouping").path("properties").path("dimensions").path("maxItems")
            .asInt().assert().isZero()
        global.path("properties").path("window").path("properties").path("limit").path("maximum")
            .asInt().assert().isEqualTo(1)
        global.path("properties").path("window").path("properties").has("cursor").assert().isFalse()
        val globalMetricVariants = global.path("properties").path("metrics").path("items").path("oneOf")
        globalMetricVariants.first().path("properties").has("field").assert().isFalse()
        globalMetricVariants.last().path("required").map { it.asText() }.assert().contains("field")
        val grouped = variants.last()
        grouped.path("properties").path("grouping").path("properties").path("kind").path("const")
            .asText().assert().isEqualTo("BY")
        grouped.path("properties").path("grouping").path("properties").path("dimensions").path("minItems")
            .asInt().assert().isEqualTo(1)
        grouped.path("properties").path("window").path("properties").path("cursor").path("\$ref")
            .asText().assert().isEqualTo("#/components/schemas/wow.api.query.AnalyticsCursor")

        val route = document.path("paths").path("/cart/snapshot/analyze").path("post")
        route.path("requestBody").path("\$ref").asText().assert()
            .isEqualTo("#/components/requestBodies/wow.AnalyticsQuery")
        route.path("responses").path("200").path("\$ref").asText().assert()
            .isEqualTo("#/components/responses/wow.AnalyticsPage")
        route.path("responses").fieldNames().asSequence().toList().sorted().assert()
            .containsExactly("200", "400", "403", "408", "429", "500", "502", "503", "504")
        listOf("400", "403", "408", "429", "500", "502", "503", "504").forEach { status ->
            val responseRef = route.path("responses").path(status).path("\$ref").asText()
            responseRef.assert().startsWith("#/components/responses/")
            val response = document.path("components").path("responses").path(responseRef.substringAfterLast('/'))
            response.path("headers").path("Wow-Error-Code").path("\$ref").asText()
                .assert().isEqualTo("#/components/headers/wow.Wow-Error-Code")
            response.path("content").path("application/json").path("schema").path("\$ref").asText()
                .assert().isEqualTo("#/components/schemas/wow.api.DefaultErrorInfo")
        }
    }

    @Test
    fun `generated query routes should declare the runtime failure status contract`() {
        val queryHandlerKeys = setOf(
            BuiltInHttpRouteHandlerKeys.Snapshot.ANALYZE,
            BuiltInHttpRouteHandlerKeys.Snapshot.COUNT,
            BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY,
            BuiltInHttpRouteHandlerKeys.Snapshot.LIST_QUERY_STATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY,
            BuiltInHttpRouteHandlerKeys.Snapshot.PAGED_QUERY_STATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE,
            BuiltInHttpRouteHandlerKeys.Snapshot.SINGLE_STATE,
            BuiltInHttpRouteHandlerKeys.Snapshot.LOAD,
            BuiltInHttpRouteHandlerKeys.Event.COUNT,
            BuiltInHttpRouteHandlerKeys.Event.LIST_QUERY,
            BuiltInHttpRouteHandlerKeys.Event.PAGED_QUERY,
            BuiltInHttpRouteHandlerKeys.Event.LOAD,
        )
        val requiredFailureStatuses = setOf("400", "403", "408", "429", "500", "502", "503", "504")
        val queryRoutes = RouterSpecs(currentContext).build().toRouteCatalog().routes
            .filter { it.handlerKey in queryHandlerKeys }

        queryRoutes.assert().isNotEmpty()
        queryRoutes.forEach { route ->
            val responseCodes = route.responses.map { it.statusCode }.toSet()
            requiredFailureStatuses.forEach { status ->
                responseCodes.assert().contains(status)
            }
        }
    }

    @Test
    fun `generated route contracts should match example domain compatibility snapshot`() {
        val routerSpecs = RouterSpecs(currentContext).build()
        val routeShape = routerSpecs.toRouteCatalog().routes.map { route ->
            mapOf(
                "id" to route.routeId,
                "path" to route.path,
                "method" to route.method,
                "accept" to route.accept,
                "parameterNames" to route.parameters.map(::parameterIdentity),
                "requestBody" to (route.requestBody != null),
                "responseCodes" to route.responses.map { it.statusCode }.sorted(),
                "tagNames" to route.tags.map { it.name }.sorted()
            )
        }.sortedWith(
            compareBy(
                { it["path"].toString() },
                { it["method"].toString() },
                { it["id"].toString() }
            )
        )

        assertContractSnapshot(
            contractJson = mapper.writeValueAsString(routeShape),
            snapshotPath = resourcePath("openapi/example-domain-contract.snapshot.json")
        )
    }

    @Test
    fun `missing snapshots should fail outside update mode`() {
        assumeFalse(System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true))
        val snapshotPath = Files.createTempDirectory("wow-openapi-snapshot")
            .resolve("missing.snapshot.json")
        val error = assertThrows<AssertionError> {
            assertContractSnapshot("""{"value":true}""", snapshotPath)
        }
        error.message.assert().contains("Missing OpenAPI compatibility snapshot")
        error.message.assert().contains("-Dwow.snapshot.update=true")
    }

    @Test
    fun `parameter identity should preserve references and inline fallback`() {
        val refParameter = Parameter().`$ref`("#/components/parameters/CommandId")
        val fallbackParameter = Parameter().required(false)

        listOf(refParameter, fallbackParameter).map(::parameterIdentity)
            .assert()
            .isEqualTo(
                listOf(
                    "ref:#/components/parameters/CommandId",
                    "<unknown-in>:<unknown-name>:false"
                )
            )
    }

    private fun parameterIdentity(parameter: Parameter): String {
        parameter.`$ref`?.takeIf { it.isNotBlank() }?.let {
            return "ref:$it"
        }
        val location = parameter.`in`?.takeIf { it.isNotBlank() } ?: "<unknown-in>"
        val name = parameter.name?.takeIf { it.isNotBlank() } ?: "<unknown-name>"
        val required = parameter.required?.toString() ?: "<unknown-required>"
        return "$location:$name:$required"
    }

    private fun parameterIdentity(parameter: HttpParameter): String {
        parameter.componentRef?.takeIf { it.isNotBlank() }?.let {
            return "ref:#/components/parameters/$it"
        }
        return "${parameter.location.name.lowercase()}:${parameter.name}:${parameter.required}"
    }
}
