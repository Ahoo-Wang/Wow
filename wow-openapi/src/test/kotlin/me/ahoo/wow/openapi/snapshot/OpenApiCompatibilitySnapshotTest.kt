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
