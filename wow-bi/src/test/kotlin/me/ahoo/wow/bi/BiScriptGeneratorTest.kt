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
import java.nio.file.Files
import java.nio.file.Path

class BiScriptGeneratorTest {
    private val aggregate = namedAggregate("aggregate")
    private val sibling = namedAggregate("sibling")

    @Test
    fun `should generate complete default sections with lossless fallbacks`() {
        val result = BiScriptGenerator().generate(setOf(aggregate))

        result.script.assert().contains("-- bi.aggregate.command --")
        result.script.assert().contains("-- bi.aggregate.stateEvent --")
        result.script.assert().contains("-- bi.aggregate.stateLast --")
        result.script.assert().contains("-- bi.aggregate.expansion --")
        result.script.assert().contains(
            "JSONExtractRaw(\"__source\".\"state\", 'mapItem') AS \"map_item\""
        )
        result.diagnostics.map(BiScriptDiagnostic::path)
            .assert()
            .containsExactly("bigDecimal", "likeMapItem", "mapItem", "numericEnum")
        result.diagnostics.all {
            it.code == BiScriptDiagnosticCode.RAW_JSON_FALLBACK &&
                it.decision == BiScriptMappingDecision.RAW_JSON
        }.assert().isTrue()
    }

    @Test
    fun `should match complete scripts for every topology`() {
        val clusterScript = BiScriptGenerator().generate(setOf(aggregate)).script
        val standaloneScript = BiScriptGenerator(
            BiScriptOptions(topology = ClickHouseTopology.Standalone)
        ).generate(setOf(aggregate)).script

        listOf(clusterScript, standaloneScript).forEach { script ->
            script.lineSequence().none { line -> line.isNotEmpty() && line.isBlank() }
                .assert()
                .isTrue()
        }
        assertSnapshot(
            "expected_bi_cluster_script.sql",
            clusterScript,
        )
        assertSnapshot(
            "expected_bi_standalone_script.sql",
            standaloneScript,
        )
    }

    @Test
    fun `should expose immutable complete statements as the only script source`() {
        val result = BiScriptGenerator().generate(setOf(aggregate))
        val statements = result.statements

        statements.forEach { statement ->
            statement.lineSequence().none { it.trimStart().startsWith("--") }.assert().isTrue()
            statement.trimEnd().endsWith(';').assert().isTrue()
            result.script.assert().contains(statement)
        }
        normalizedSql(result.script).assert().isEqualTo(
            normalizedSql(statements.joinToString("\n\n"))
        )

        statements.indexOfFirst { it.contains("CREATE DATABASE IF NOT EXISTS \"bi_db\"") }
            .assert().isEqualTo(0)
        statements.indexOfFirst { it.contains("CREATE DATABASE IF NOT EXISTS \"bi_db_consumer\"") }
            .assert().isEqualTo(1)
        indexOfStatement(statements, "DROP TABLE IF EXISTS \"bi_db\".\"bi_aggregate_command\"")
            .assert().isLessThan(
                indexOfStatement(
                    statements,
                    "CREATE TABLE IF NOT EXISTS \"bi_db\".\"bi_aggregate_command_local\"",
                )
            )
        indexOfStatement(statements, "CREATE TABLE IF NOT EXISTS \"bi_db\".\"bi_aggregate_command_local\"")
            .assert().isLessThan(
                indexOfStatement(
                    statements,
                    "CREATE TABLE IF NOT EXISTS \"bi_db\".\"bi_aggregate_state_local\"",
                )
            )
        indexOfStatement(statements, "CREATE TABLE IF NOT EXISTS \"bi_db\".\"bi_aggregate_state_last_local\"")
            .assert().isLessThan(
                indexOfStatement(
                    statements,
                    "CREATE VIEW IF NOT EXISTS \"bi_db\".\"bi_aggregate_state_last_root\"",
                )
            )

        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (statements as MutableList<String>).clear()
        }
    }

    @Test
    fun `should apply every rendering option`() {
        val result = BiScriptGenerator(
            BiScriptOptions(
                database = "analytics\"db",
                consumerDatabase = "consumer db",
                topology = ClickHouseTopology.Cluster(
                    name = "cluster'name",
                    installation = "install/name",
                    shard = "shard'name",
                    replica = "replica'name",
                ),
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
            unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
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
        val result = BiScriptGenerator().generate(
            linkedSetOf(namedAggregate("generic-object-map"), aggregate)
        )

        result.diagnostics.map(BiScriptDiagnostic::aggregate)
            .assert()
            .containsExactly(
                "bi-service.aggregate",
                "bi-service.aggregate",
                "bi-service.aggregate",
                "bi-service.aggregate",
                "bi-service.generic-object-map",
                "bi-service.generic-object-map",
            )
        result.diagnostics.map(BiScriptDiagnostic::path).assert().containsExactly(
            "bigDecimal",
            "likeMapItem",
            "mapItem",
            "numericEnum",
            "genericValues",
            "values",
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

    private fun assertSnapshot(name: String, actual: String) {
        val path = Path.of("src/test/resources", name)
        if (System.getenv("UPDATE_BI_SCRIPT_SNAPSHOTS") == "true") {
            Files.writeString(path, actual)
        }
        actual.assert().isEqualTo(Files.readString(path))
    }

    private fun normalizedSql(script: String): String = script.lineSequence()
        .map(String::trimEnd)
        .filterNot { it.isBlank() || it.trimStart().startsWith("--") }
        .joinToString("\n")

    private fun indexOfStatement(statements: List<String>, fragment: String): Int =
        statements.indexOfFirst { it.contains(fragment) }.also { index ->
            check(index >= 0) { "Statement containing [$fragment] was not generated." }
        }
}
