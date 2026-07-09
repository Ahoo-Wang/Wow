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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BiScriptGeneratorTest {
    private val aggregate = namedAggregate("aggregate")
    private val sibling = namedAggregate("sibling")

    @Test
    fun `should generate the complete default script`() {
        val result = BiScriptGenerator().generate(setOf(aggregate))
        val expectedScript = requireNotNull(
            javaClass.classLoader.getResource("expected_bi_script.sql")
        ).readText().trim()

        result.script.trim().assert().isEqualTo(expectedScript)
        result.diagnostics.map(BiScriptDiagnostic::path)
            .assert()
            .containsExactly("likeMapItem", "mapItem")
    }

    @Test
    fun `should apply every rendering option`() {
        val result = BiScriptGenerator(
            BiScriptOptions(
                database = "analytics\"db",
                consumerDatabase = "consumer db",
                cluster = "cluster'name",
                installation = "install/name",
                shard = "shard'name",
                replica = "replica'name",
                timezone = "UTC",
                kafkaBootstrapServers = "kafka\\host:9092",
                topicPrefix = "custom'prefix.",
            )
        ).generate(setOf(aggregate))

        result.script.assert().contains("\"analytics\\\"db\"")
        result.script.assert().contains("\"consumer db\"")
        result.script.assert().contains("ON CLUSTER 'cluster''name'")
        result.script.assert().contains("/install/name/cluster''name/tables/shard''name")
        result.script.assert().contains("'replica''name'")
        result.script.assert().contains("DateTime('UTC')")
        result.script.assert().contains("Kafka('kafka\\\\host:9092'")
        result.script.assert().contains("'custom''prefix.bi.aggregate.command'")
    }

    @Test
    fun `should generate identical result and diagnostics for reversed aggregate sets`() {
        val options = BiScriptOptions(
            unsupportedTypeStrategy = UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC,
        )
        val forward = BiScriptGenerator(options).generate(linkedSetOf(aggregate, sibling))
        val reverse = BiScriptGenerator(options).generate(linkedSetOf(sibling, aggregate))

        forward.assert().isEqualTo(reverse)
        forward.script.indexOf("-- bi.aggregate.clear --")
            .assert()
            .isLessThan(forward.script.indexOf("-- bi.sibling.clear --"))
    }

    @Test
    fun `should return an immutable ordered diagnostics list`() {
        val result = BiScriptGenerator(
            BiScriptOptions(objectMapStrategy = ObjectMapStrategy.STRING_VALUE_WITH_DIAGNOSTIC)
        ).generate(linkedSetOf(namedAggregate("generic-object-map"), aggregate))

        result.diagnostics.map(BiScriptDiagnostic::aggregate)
            .assert()
            .containsExactly(
                "bi-service.aggregate",
                "bi-service.aggregate",
                "bi-service.generic-object-map",
            )
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.diagnostics as MutableList<BiScriptDiagnostic>).clear()
        }
    }

    @Test
    fun `should generate only the global section for an empty aggregate set`() {
        val result = BiScriptGenerator().generate(emptySet())

        result.script.assert().contains("-- global --")
        result.script.assert().contains("CREATE DATABASE IF NOT EXISTS")
        result.script.assert().contains("-- clear --")
        result.script.assert().doesNotContain(".command --")
        result.diagnostics.assert().isEmpty()
    }

    private fun namedAggregate(name: String): NamedAggregate =
        MetadataSearcher.localAggregates.single { it.aggregateName == name }
}
