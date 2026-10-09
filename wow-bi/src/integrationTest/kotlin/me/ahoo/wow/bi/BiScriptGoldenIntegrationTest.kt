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
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.layout.BiTableNaming
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.quoteIdentifier
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.stringLiteral
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Pins the generated script of every DEPLOY/RESET scenario against a real ClickHouse catalog.
 *
 * The scenarios go through the public generator and inspector only, so the snapshots stay valid while the
 * internals are rewritten. Regenerate them with `WOW_BI_GOLDEN_UPDATE=true` and review the diff.
 */
class BiScriptGoldenIntegrationTest {
    private data class TopologyCase(
        val name: String,
        val topology: ClickHouseTopology,
        val configureContainer: ClickHouseContainer.() -> Unit,
    )

    @TestFactory
    fun scenarios(): List<DynamicTest> = topologyCases().map { case ->
        DynamicTest.dynamicTest(case.name) { runScenarios(case) }
    }

    @Suppress("LongMethod")
    private fun runScenarios(case: TopologyCase) {
        val options = BiScriptOptions(
            database = DATABASE,
            consumerDatabase = CONSUMER_DATABASE,
            topology = case.topology,
            timezone = "UTC",
            consumerGroupNamespace = "golden",
        )
        val naming = BiTableNaming(options)
        val generator = BiScriptGenerator(options)
        val both = setOf(NULLABLE, SIBLING)
        val failures = mutableListOf<String>()
        ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE)).apply(case.configureContainer).use { clickHouse ->
            clickHouse.start()
            DriverManager.getConnection(clickHouse.jdbcUrl, clickHouse.username, clickHouse.password).use { connection ->
                ClickHouseBiDeploymentInspector(
                    ClickHouseClientOptions(
                        endpoints = listOf(URI.create(clickHouse.httpUrl)),
                        username = clickHouse.username,
                        password = clickHouse.password,
                    )
                ).use { inspector ->
                    fun step(
                        name: String,
                        aggregates: Set<NamedAggregate>,
                        operation: BiScriptOperation = BiScriptOperation.Deploy,
                        execute: (BiScriptResult) -> List<String> = BiScriptResult::statements,
                    ) {
                        val preparation = generator.prepare(aggregates)
                        val inspection = inspector.inspect(options, operation, preparation).block()!!
                        val result = generator.generate(preparation, operation, inspection)
                        compareGolden("${case.name}/$name.sql", result.toGolden(options))?.let(failures::add)
                        execute(result).forEach(connection::executeSql)
                    }

                    step("01-deploy-initial", setOf(NULLABLE))
                    step("02-deploy-idempotent", setOf(NULLABLE))

                    connection.driftRootView(naming)
                    step("03-deploy-view-drift", setOf(NULLABLE))

                    val driftTarget = connection.driftStateLastConsumerTarget(naming)
                    step("04-deploy-consumer-drift", setOf(NULLABLE))
                    connection.executeSql("DROP TABLE ${qualified(DATABASE, driftTarget)}")

                    step("05-deploy-add-aggregate", both)
                    step("06-deploy-remove-aggregate", setOf(NULLABLE))

                    step("07-reset-interrupted", setOf(NULLABLE), BiScriptOperation.Reset(true)) { result ->
                        result.statements.take(result.statements.indexOfFirst { it.isAnchor("RESETTING") } + 1)
                    }
                    step("08-reset-resumed", setOf(NULLABLE), BiScriptOperation.Reset(true))
                    step("09-deploy-after-reset", setOf(NULLABLE))
                }
            }
        }
        failures.assert().isEmpty()
    }

    private fun Connection.driftRootView(naming: BiTableNaming) {
        val view = naming.toTableName(NULLABLE, "state_last_root")
        val store = naming.toTableName(NULLABLE, "state_last_store")
        executeSql(
            "CREATE OR REPLACE VIEW ${qualified(DATABASE, view)} AS " +
                "SELECT * FROM ${qualified(DATABASE, store)} FINAL COMMENT ${stringLiteral(comment(DATABASE, view))}"
        )
    }

    private fun Connection.driftStateLastConsumerTarget(naming: BiTableNaming): String {
        val consumer = naming.toTableName(NULLABLE, "state_last_consumer")
        val stateStore = naming.toTableName(NULLABLE, "state_store")
        val stateLastStore = naming.toTableName(NULLABLE, "state_last_store")
        val driftTarget = "__wow_bi_golden_drift_target"
        val consumerComment = comment(CONSUMER_DATABASE, consumer)
        executeSql("DROP VIEW ${qualified(CONSUMER_DATABASE, consumer)} SYNC")
        executeSql(
            "CREATE TABLE ${qualified(DATABASE, driftTarget)} ENGINE = Memory AS " +
                "SELECT * FROM ${qualified(DATABASE, stateLastStore)} WHERE 0"
        )
        executeSql(
            "CREATE MATERIALIZED VIEW ${qualified(CONSUMER_DATABASE, consumer)} " +
                "TO ${qualified(DATABASE, driftTarget)} AS (SELECT * FROM ${qualified(DATABASE, stateStore)}) " +
                "COMMENT ${stringLiteral(consumerComment)}"
        )
        return driftTarget
    }

    private fun Connection.comment(database: String, table: String): String =
        createStatement().use { statement ->
            statement.executeQuery(
                "SELECT comment FROM system.tables WHERE database = ${stringLiteral(database)} " +
                    "AND name = ${stringLiteral(table)}"
            ).use { rows ->
                check(rows.next()) { "Missing $database.$table" }
                rows.getString(1)
            }
        }

    private fun BiScriptResult.toGolden(options: BiScriptOptions): String = buildString {
        appendLine("-- operation: $operation, destructive: $destructive")
        diagnostics.forEach { diagnostic ->
            appendLine("-- diagnostic: ${diagnostic.code} ${diagnostic.decision} ${diagnostic.aggregate} ${diagnostic.path}")
        }
        append(script.normalizedResetIdentity(BiConsumerIdentity.deterministic(BiDeploymentDescriptor.from(options))))
    }

    /** RESET draws a random consumer identity; pin it so the snapshot is deterministic. */
    private fun String.normalizedResetIdentity(deterministic: BiConsumerIdentity): String =
        CONSUMER_IDENTITY.findAll(this)
            .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
            .distinct()
            .filter { identity -> identity != deterministic.value }
            .fold(this) { script, identity -> script.replace(identity, RANDOM_IDENTITY_PLACEHOLDER) }

    private fun compareGolden(name: String, actual: String): String? {
        val path = GOLDEN_ROOT.resolve(name)
        if (System.getenv(UPDATE_ENV) == "true") {
            Files.createDirectories(path.parent)
            Files.writeString(path, actual)
            return null
        }
        if (!Files.exists(path)) {
            return "Missing golden $path; run with $UPDATE_ENV=true"
        }
        return if (Files.readString(path) == actual) null else "Golden mismatch: $path"
    }

    private fun String.isAnchor(phase: String): Boolean =
        contains(ANCHOR_NAME) && contains("\"phase\":\"$phase\"")

    private fun topologyCases(): List<TopologyCase> = listOf(
        TopologyCase(
            name = "standalone",
            topology = ClickHouseTopology.Standalone,
            configureContainer = {},
        ),
        TopologyCase(
            name = "cluster",
            topology = ClickHouseTopology.Cluster(name = CLUSTER, installation = "golden"),
            configureContainer = {
                withCopyFileToContainer(
                    MountableFile.forClasspathResource(CLUSTER_CONFIG_RESOURCE),
                    CLUSTER_CONFIG_PATH,
                )
            },
        ),
    )

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val CLUSTER = "test_cluster"
        const val CLUSTER_CONFIG_RESOURCE = "clickhouse-test-cluster.xml"
        const val CLUSTER_CONFIG_PATH = "/etc/clickhouse-server/config.d/clickhouse-test-cluster.xml"
        const val DATABASE = "bi_golden"
        const val CONSUMER_DATABASE = "bi_golden_consumer"
        const val ANCHOR_NAME = "__wow_bi_deployment"
        const val UPDATE_ENV = "WOW_BI_GOLDEN_UPDATE"
        const val RANDOM_IDENTITY_PLACEHOLDER = "0000000000000000000000000000ffff"
        val CONSUMER_IDENTITY = Regex("wow-bi\\.([0-9a-f]{32})\\.|\"consumerIdentity\":\"([0-9a-f]{32})\"")
        val GOLDEN_ROOT: Path = Path.of("src/integrationTest/resources/golden")
        val NULLABLE: NamedAggregate = aggregateMetadata<ClickHouseExpansionAggregate, ClickHouseExpansionState>()
        val SIBLING: NamedAggregate = aggregateMetadata<BiGoldenSiblingAggregate, BiGoldenSiblingState>()

        fun qualified(database: String, table: String): String =
            "${quoteIdentifier(database)}.${quoteIdentifier(table)}"
    }
}

private fun Connection.executeSql(sql: String) {
    createStatement().use { statement -> statement.execute(sql) }
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
internal class BiGoldenSiblingAggregate(private val state: BiGoldenSiblingState)

internal class BiGoldenSiblingState(override val id: String) : Identifier {
    val name: String = ""
    val items: List<BiGoldenSiblingItem> = emptyList()
}

internal data class BiGoldenSiblingItem(val sku: String, val quantity: Int)
