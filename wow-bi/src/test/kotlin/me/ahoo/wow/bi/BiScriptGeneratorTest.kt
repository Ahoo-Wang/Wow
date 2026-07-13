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
        val result = generator().generate(setOf(aggregate))

        result.script.assert().contains("-- bi.aggregate.command --")
        result.script.assert().contains("-- bi.aggregate.stateStorage --")
        result.script.assert().contains("-- bi.aggregate.stateLast --")
        result.script.assert().contains("-- bi.aggregate.stateIngress --")
        result.script.assert().contains("-- bi.aggregate.expansion --")
        result.script.assert().contains(
            "JSONExtractRaw(\"__source\".\"state\", 'mapItem') AS \"map_item\""
        )
        result.diagnostics.map(BiScriptDiagnostic::path)
            .assert()
            .containsExactly("topology.cluster", "bigDecimal", "likeMapItem", "mapItem", "numericEnum")
        result.diagnostics.first().code.assert()
            .isEqualTo(BiScriptDiagnosticCode.CLUSTER_INTERNAL_REPLICATION_REQUIRED)
        result.diagnostics.drop(1).all {
            it.code == BiScriptDiagnosticCode.RAW_JSON_FALLBACK &&
                it.decision == BiScriptMappingDecision.RAW_JSON
        }.assert().isTrue()
    }

    @Test
    fun `should match complete scripts for every topology`() {
        val clusterScript = generator().generate(setOf(aggregate)).script
        val standaloneScript = BiScriptGenerator(
            BiScriptOptions(topology = ClickHouseTopology.Standalone, consumerGroupNamespace = "test")
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
        val result = generator().generate(setOf(aggregate))
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
        statements.none {
            it.startsWith("DROP TABLE") && it.contains("\"bi_db\".")
        }.assert().isTrue()
        indexOfStatement(statements, "CREATE TABLE IF NOT EXISTS \"bi_db\".\"bi_aggregate_command_store_local\"")
            .assert().isLessThan(
                indexOfStatement(
                    statements,
                    "CREATE TABLE IF NOT EXISTS \"bi_db\".\"bi_aggregate_state_store_local\"",
                )
            )
        indexOfStatement(statements, "CREATE TABLE IF NOT EXISTS \"bi_db\".\"bi_aggregate_state_last_store_local\"")
            .assert().isLessThan(
                indexOfStatement(
                    statements,
                    "CREATE OR REPLACE VIEW \"bi_db\".\"bi_aggregate_state_last_root\"",
                )
            )

        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (statements as MutableList<String>).clear()
        }
    }

    @Test
    fun `should render destructive statements only for an explicit reset`() {
        val deploy = generator().generate(setOf(aggregate))
        val reset = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Reset(
                previousManifest = deploy.manifest,
                replayFromEarliestConfirmed = true,
            ),
        )

        deploy.destructive.assert().isFalse()
        deploy.script.assert().doesNotContain("DROP TABLE IF EXISTS \"bi_db\".")
        reset.destructive.assert().isTrue()
        reset.manifest.consumerGeneration.assert().isNotEqualTo(deploy.manifest.consumerGeneration)
        generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy(reset.manifest),
        ).manifest.consumerGeneration.assert().isEqualTo(reset.manifest.consumerGeneration)
        reset.script.assert().contains("DROP TABLE")
        reset.script.assert().doesNotContain("wow-bi.test.")
        assertThrows<IllegalArgumentException> {
            BiScriptOperation.Reset(
                previousManifest = deploy.manifest,
                replayFromEarliestConfirmed = false,
            )
        }
    }

    @Test
    fun `should isolate keeper offsets for an explicit reset`() {
        val options = BiScriptOptions(
            consumerGroupNamespace = "test",
            kafkaOffsetStorage = KafkaOffsetStorage.KEEPER,
        )
        val generator = BiScriptGenerator(options)
        val deploy = generator.generate(setOf(aggregate))
        val reset = generator.generate(
            setOf(aggregate),
            BiScriptOperation.Reset(
                previousManifest = deploy.manifest,
                replayFromEarliestConfirmed = true,
            ),
        )

        reset.script.assert().contains(
            "kafka_keeper_path = '/clickhouse/wow-bi/",
            "kafka_replica_name = '{replica}'",
        )

        val standalone = BiScriptGenerator(
            options.copy(topology = ClickHouseTopology.Standalone)
        ).generate(setOf(aggregate))
        standalone.script.assert()
            .contains("kafka_replica_name = '")
            .doesNotContain("kafka_replica_name = '{replica}'")
    }

    @Test
    fun `should drop stale expansion views from the previous manifest without dropping data tables`() {
        val current = generator().generate(setOf(aggregate))
        val aggregateManifest = current.manifest.aggregates.single()
        val previous = current.manifest.copy(
            aggregates = listOf(
                aggregateManifest.copy(
                    expansionViews = aggregateManifest.expansionViews + "bi_aggregate_state_last_root_removed"
                )
            )
        )

        val reconciled = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy(previous),
        )

        reconciled.script.assert().contains(
            "DROP VIEW IF EXISTS \"bi_db\".\"bi_aggregate_state_last_root_removed\""
        )
        reconciled.script.assert().doesNotContain(
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_aggregate_command\""
        )

        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy(
                    current.manifest.copy(
                        deployment = current.manifest.deployment.copy(
                            consumerGroupNamespace = "another-deployment"
                        )
                    )
                ),
            )
        }
    }

    @Test
    fun `should retire removed aggregate consumers and retain their data tables`() {
        val previous = generator().generate(setOf(aggregate, sibling)).manifest
        val result = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy(previous),
        )

        result.script.assert().contains(
            "DROP VIEW IF EXISTS \"bi_db_consumer\".\"bi_sibling_command_consumer\"",
            "DROP TABLE IF EXISTS \"bi_db_consumer\".\"bi_sibling_command_queue\"",
        )
        result.script.assert().doesNotContain(
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_command\"",
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_state\"",
        )
        result.diagnostics.single { it.code == BiScriptDiagnosticCode.ORPHANED_DATA_TABLE }
            .aggregate.assert().isEqualTo("bi.sibling")
    }

    @Test
    fun `should delete manifest orphans during reset`() {
        val previous = generator().generate(setOf(aggregate, sibling)).manifest
        val result = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Reset(
                previousManifest = previous,
                replayFromEarliestConfirmed = true,
            ),
        )

        result.script.assert().contains(
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_command_store\"",
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_state_store\"",
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_state_last_store\"",
        )
    }

    @Test
    fun `should retain orphan ownership until reset deletes its stores`() {
        val initial = generator().generate(setOf(aggregate, sibling)).manifest
        val removed = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy(initial),
        ).manifest
        val redeployed = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy(removed),
        ).manifest

        removed.retainedAggregates.map(BiAggregateManifest::aggregate)
            .assert().isEqualTo(listOf("bi.sibling"))
        redeployed.retainedAggregates.assert().isEqualTo(removed.retainedAggregates)
        generator().generate(
            setOf(aggregate, sibling),
            BiScriptOperation.Deploy(redeployed),
        ).manifest.retainedAggregates.assert().isEmpty()

        val reset = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Reset(
                previousManifest = redeployed,
                replayFromEarliestConfirmed = true,
            ),
        )
        reset.script.assert().contains(
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_command_store\"",
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_state_store\"",
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_state_last_store\"",
        )
        reset.manifest.retainedAggregates.assert().isEmpty()
    }

    @Test
    fun `should reject previous and current lifecycle object ownership collisions`() {
        val current = generator().generate(setOf(aggregate))
        val colliding = BiAggregateManifest(
            aggregate = "bi-service.aggregate",
            tablePrefix = "bi_aggregate",
            expansionViews = listOf("bi_aggregate_state_last_root"),
        )

        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy(current.manifest.copy(aggregates = listOf(colliding))),
            )
        }.message.assert().contains("BI object name collision")
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
                ),
                timezone = "UTC",
                kafkaBootstrapServers = "kafka\\host:9092",
                topicPrefix = "custom'prefix.",
                consumerGroupNamespace = "blue",
            )
        ).generate(setOf(aggregate))

        result.script.assert().contains("\"analytics\\\"db\"")
        result.script.assert().contains("\"consumer db\"")
        result.script.assert().contains("ON CLUSTER 'cluster''name'")
        result.script.assert().contains("/install/name/cluster''name/tables/{shard}/{database}/{table}")
        result.script.assert().contains("'{replica}'")
        result.script.assert().contains("DateTime64(3, 'UTC')")
        result.script.assert().contains("Kafka('kafka\\\\host:9092'")
        result.script.assert().contains("'custom''prefix.bi.aggregate.command'")
    }

    @Test
    fun `should generate identical result and diagnostics for reversed aggregate sets`() {
        val options = BiScriptOptions(
            consumerGroupNamespace = "test",
            unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
        )
        val forward = BiScriptGenerator(options).generate(linkedSetOf(aggregate, sibling))
        val reverse = BiScriptGenerator(options).generate(linkedSetOf(sibling, aggregate))

        forward.assert().isEqualTo(reverse)
        forward.script.indexOf("-- bi.aggregate.command --")
            .assert()
            .isLessThan(forward.script.indexOf("-- bi.sibling.command --"))
    }

    @Test
    fun `should return an immutable ordered diagnostics list`() {
        val result = generator().generate(
            linkedSetOf(namedAggregate("generic-object-map"), aggregate)
        )

        result.diagnostics.map(BiScriptDiagnostic::aggregate)
            .assert()
            .containsExactly(
                "*",
                "bi-service.aggregate",
                "bi-service.aggregate",
                "bi-service.aggregate",
                "bi-service.aggregate",
                "bi-service.generic-object-map",
                "bi-service.generic-object-map",
            )
        result.diagnostics.map(BiScriptDiagnostic::path).assert().containsExactly(
            "topology.cluster",
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
        result.script.assert().contains("-- lifecycle --")
        result.script.assert().doesNotContain(".command --")
        result.diagnostics.assert().isEmpty()
    }

    private fun namedAggregate(name: String): NamedAggregate =
        MetadataSearcher.localAggregates.single { it.aggregateName == name }

    private fun generator(
        options: BiScriptOptions = BiScriptOptions(consumerGroupNamespace = "test"),
    ): BiScriptGenerator = BiScriptGenerator(options)

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
