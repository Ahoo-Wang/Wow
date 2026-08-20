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
package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.util.jar.JarFile

class QueryGatewayConfigurationMetadataTest {
    @Test
    fun `published starter jar describes every query gateway configuration leaf`() {
        val publishedJar = File(requireNotNull(System.getProperty("wow.starter.jar")))
        publishedJar.isFile.assert().isTrue()
        val metadataDocuments = JarFile(publishedJar).use { jar ->
            jar.getJarEntry(QueryGatewayProperties::class.java.name.replace('.', '/') + ".class")
                .assert().isNotNull()
            METADATA_PATHS.map { path ->
                val entry = jar.getJarEntry(path)
                entry.assert().isNotNull()
                ObjectMapper().readTree(jar.getInputStream(entry))
            }
        }
        val gatewayGroups = metadataDocuments.flatMap { it["groups"] }.associateByName()
            .filterKeys { it.startsWith(GATEWAY_PREFIX) }
        gatewayGroups.keys.assert().containsExactlyInAnyOrder(
            GATEWAY_PREFIX,
            "$GATEWAY_PREFIX.system-budget"
        )

        val gatewayProperties = metadataDocuments.flatMap { it["properties"] }.associateByName()
            .filterKeys { it.startsWith("$GATEWAY_PREFIX.") }
        gatewayProperties.keys.assert().containsExactlyInAnyOrder(*EXPECTED_PROPERTIES.keys.toTypedArray())
        EXPECTED_PROPERTIES.forEach { (name, expected) ->
            gatewayProperties.getValue(name).let { property ->
                property["type"].asString().assert().isEqualTo(expected.type)
                property["defaultValue"].let { defaultValue ->
                    if (expected.defaultValue == null) {
                        defaultValue.assert().isNull()
                    } else {
                        defaultValue.asString().assert().isEqualTo(expected.defaultValue)
                    }
                }
                property["description"].asString().isNotBlank().assert().isTrue()
            }
        }
    }

    private fun Iterable<JsonNode>.associateByName(): Map<String, JsonNode> = associateBy { it["name"].asString() }

    private data class ExpectedProperty(val type: String, val defaultValue: String?)

    private companion object {
        val METADATA_PATHS: List<String> = listOf(
            "META-INF/spring-configuration-metadata.json",
            "META-INF/additional-spring-configuration-metadata.json"
        )
        const val GATEWAY_PREFIX: String = "wow.query.gateway"
        val EXPECTED_PROPERTIES: Map<String, ExpectedProperty> = linkedMapOf(
            "$GATEWAY_PREFIX.max-depth" to ExpectedProperty("java.lang.Integer", "64"),
            "$GATEWAY_PREFIX.max-nodes" to ExpectedProperty("java.lang.Integer", "10000"),
            "$GATEWAY_PREFIX.max-membership-items" to ExpectedProperty("java.lang.Integer", "10000"),
            "$GATEWAY_PREFIX.max-native-parameter-bytes" to ExpectedProperty("java.lang.Long", "1048576"),
            "$GATEWAY_PREFIX.system-budget.timeout" to ExpectedProperty("java.time.Duration", null),
            "$GATEWAY_PREFIX.system-budget.max-results" to ExpectedProperty("java.lang.Long", null),
            "$GATEWAY_PREFIX.system-budget.max-cost" to ExpectedProperty("java.lang.Long", null),
            "$GATEWAY_PREFIX.enabled-capabilities" to ExpectedProperty("java.util.Set<java.lang.String>", null)
        )
    }
}
