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
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

@Suppress("LargeClass")
class BiScriptGeneratorTest {
    private val aggregate = namedAggregate("aggregate")
    private val sibling = namedAggregate("sibling")

    @Test
    fun `should reuse a request scoped preparation and reject different options`() {
        val originalGenerator = generator()
        val requestAggregates = linkedSetOf(aggregate)
        val preparation = originalGenerator.prepare(requestAggregates)
        requestAggregates.clear()

        preparation.namedAggregates.assert().containsExactly(aggregate)
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (preparation.desiredObjects as MutableList<DesiredBiObject>).clear()
        }

        originalGenerator.generate(preparation).assert()
            .isEqualTo(originalGenerator.generate(setOf(aggregate)))

        val changedOptions = BiScriptOptions(
            consumerGroupNamespace = "test",
            topicPrefix = "changed.",
            maxExpansionDepth = 1,
        )
        assertThrows<IllegalArgumentException> {
            BiScriptGenerator(changedOptions).generate(preparation)
        }.message.assert().contains("prepared with different BI script options")
    }

    @Test
    fun `should generate complete default sections with lossless fallbacks`() {
        val result = generator().generate(setOf(aggregate))

        result.script.assert().contains("-- bi.aggregate.commandStorage --")
        result.script.assert().contains("-- bi.aggregate.commandPublic --")
        result.script.assert().contains("-- bi.aggregate.commandIngress --")
        result.script.assert().contains("-- bi.aggregate.stateStorage --")
        result.script.assert().contains("-- bi.aggregate.stateLast --")
        result.script.assert().contains("-- bi.aggregate.stateIngress --")
        result.script.assert().contains("-- bi.aggregate.expansion --")
        result.script.assert().contains(
            "JSONExtractRaw(\"__source\".\"state\", 'mapItem') AS \"map_item\""
        )
        result.diagnostics.map(BiScriptDiagnostic::path)
            .assert()
            .containsExactly(
                "topology.cluster",
                "lifecycle.inspection",
                "bigDecimal",
                "likeMapItem",
                "mapItem",
                "numericEnum",
            )
        result.diagnostics.first().code.assert()
            .isEqualTo(BiScriptDiagnosticCode.CLUSTER_INTERNAL_REPLICATION_REQUIRED)
        result.diagnostics.drop(2).all {
            it.code == BiScriptDiagnosticCode.RAW_JSON_FALLBACK &&
                it.decision == BiScriptMappingDecision.RAW_JSON
        }.assert().isTrue()
    }

    @Test
    fun `bootstrap registry creation should be retryable before its first head is written`() {
        val options = BiScriptOptions(consumerGroupNamespace = "test")
        val registryName = BiOwnershipRegistry.empty(
            BiDeploymentDescriptor.from(options).deploymentId
        ).name

        generator(options).generate(setOf(aggregate)).script.assert().contains(
            "CREATE TABLE IF NOT EXISTS \"${options.consumerDatabase}\".\"$registryName\""
        )
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
        indexOfStatement(statements, "CREATE TABLE \"bi_db\".\"bi_aggregate_command_store_local\"")
            .assert().isLessThan(
                indexOfStatement(
                    statements,
                    "CREATE TABLE \"bi_db\".\"bi_aggregate_state_store_local\"",
                )
            )
        indexOfStatement(statements, "CREATE TABLE \"bi_db\".\"bi_aggregate_state_last_store_local\"")
            .assert().isLessThan(
                indexOfStatement(
                    statements,
                    "CREATE VIEW \"bi_db\".\"bi_aggregate_state_last_root\"",
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
        val inspection = availableInspection(anchor())
        val reset = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Reset(replayFromEarliestConfirmed = true),
            inspection,
        )

        deploy.destructive.assert().isFalse()
        deploy.script.assert().doesNotContain("DROP TABLE IF EXISTS \"bi_db\".")
        reset.destructive.assert().isTrue()
        reset.script.assert().doesNotContain("DROP VIEW IF EXISTS \"bi_db_consumer\".\"__wow_bi_deployment\"")
        reset.script.assert().doesNotContain("wow-bi.test.")
        assertThrows<IllegalArgumentException> {
            BiScriptOperation.Reset(replayFromEarliestConfirmed = false)
        }
        assertThrows<IllegalArgumentException> {
            generator().generate(setOf(aggregate), BiScriptOperation.Reset(true))
        }
    }

    @Test
    fun `should persist resetting intent before destructive work and stabilize before Kafka ingress`() {
        val inspection = availableInspection(
            anchor(),
            observed("bi_db", "bi_sibling_command_store", BiObjectKind.STORE, "bi-service.sibling"),
            observed(
                "bi_db_consumer",
                "bi_sibling_command_queue",
                BiObjectKind.QUEUE,
                "bi-service.sibling",
            ),
        )

        val reset = generator().generate(setOf(aggregate), BiScriptOperation.Reset(true), inspection)
        val statements = reset.statements
        val resettingAnchor = indexOfAnchorStatement(statements, BiDeploymentPhase.RESETTING)
        val stableAnchor = indexOfAnchorStatement(statements, BiDeploymentPhase.STABLE)
        val firstDestructiveDrop = indexOfStatement(
            statements,
            "DROP TABLE IF EXISTS \"bi_db_consumer\".\"bi_sibling_command_queue\"",
        )
        val durableGraph = indexOfStatement(
            statements,
            "CREATE OR REPLACE VIEW \"bi_db\".\"bi_aggregate_state_last_root\"",
        )
        val commandQueue = indexOfStatement(
            statements,
            "CREATE TABLE \"bi_db_consumer\".\"bi_aggregate_command_queue\"",
        )
        val stateQueue = indexOfStatement(
            statements,
            "CREATE TABLE \"bi_db_consumer\".\"bi_aggregate_state_queue\"",
        )

        resettingAnchor.assert().isLessThan(firstDestructiveDrop)
        firstDestructiveDrop.assert().isLessThan(durableGraph)
        durableGraph.assert().isLessThan(stableAnchor)
        stableAnchor.assert().isLessThan(commandQueue)
        stableAnchor.assert().isLessThan(stateQueue)
        val resetSql = statements.joinToString("\n")
        resetSql.assert()
            .doesNotContain("DROP VIEW IF EXISTS \"bi_db_consumer\".\"__wow_bi_deployment\"")
            .contains(
                "DROP TABLE IF EXISTS \"bi_db_consumer\".\"bi_sibling_command_queue\" " +
                    "ON CLUSTER '{cluster}' SYNC",
            )
        statements.filter { statement -> statement.contains("__wow_bi_deployment") }.assert().hasSize(2)
        statements.filter { statement -> statement.contains("__wow_bi_deployment") }.all { statement ->
            statement.contains("ON CLUSTER '{cluster}'")
        }.assert().isTrue()
        listOf(
            "bi_aggregate_command_consumer",
            "bi_aggregate_state_consumer",
        ).forEach { consumer ->
            stableAnchor.assert().isLessThan(indexOfStatement(statements, consumer))
        }
    }

    @Test
    fun `should isolate keeper offsets for an explicit reset`() {
        val options = BiScriptOptions(
            consumerGroupNamespace = "test",
            kafkaOffsetStorage = KafkaOffsetStorage.KEEPER,
        )
        val generator = BiScriptGenerator(options)
        val reset = generator.generate(
            setOf(aggregate),
            BiScriptOperation.Reset(replayFromEarliestConfirmed = true),
            availableInspection(anchor(options)),
        )

        reset.script.assert().contains(
            "kafka_keeper_path = '/clickhouse/wow-bi/",
            "kafka_replica_name = '{replica}'",
            "SETTINGS allow_experimental_kafka_offsets_storage_in_keeper = 1",
        )
        Regex("SETTINGS allow_experimental_kafka_offsets_storage_in_keeper = 1")
            .findAll(reset.script)
            .count()
            .assert().isEqualTo(2)

        val standalone = BiScriptGenerator(
            options.copy(topology = ClickHouseTopology.Standalone)
        ).generate(setOf(aggregate))
        standalone.script.assert()
            .contains("kafka_replica_name = '")
            .doesNotContain("kafka_replica_name = '{replica}'")
        Regex("SETTINGS allow_experimental_kafka_offsets_storage_in_keeper = 1")
            .findAll(standalone.script)
            .count()
            .assert().isEqualTo(2)
        generator().generate(setOf(aggregate)).script.assert()
            .doesNotContain("allow_experimental_kafka_offsets_storage_in_keeper")
    }

    @Test
    fun `should keep offline deploy explicit and non-authoritative`() {
        val result = generator().generate(setOf(aggregate))

        result.diagnostics.single { it.code == BiScriptDiagnosticCode.INSPECTION_UNAVAILABLE }
            .decision.assert().isEqualTo(BiScriptMappingDecision.RECONCILIATION_SKIPPED)
        result.script.assert().contains("__wow_bi_deployment", "wow-bi:")
        result.statements
            .drop(2)
            .filterNot { it.contains("__wow_bi_registry_") }
            .joinToString("\n")
            .assert()
            .doesNotContain("DROP ", "CREATE OR REPLACE", "IF NOT EXISTS")
            .contains(
                "CREATE TABLE \"bi_db\".\"bi_aggregate_command_store_local\"",
                "CREATE TABLE \"bi_db_consumer\".\"bi_aggregate_command_queue\"",
                "CREATE MATERIALIZED VIEW \"bi_db_consumer\".\"bi_aggregate_command_consumer\"",
                "CREATE VIEW \"bi_db_consumer\".\"__wow_bi_deployment\"",
            )
    }

    @Test
    fun `should preserve Kafka queues during an authoritative deploy`() {
        val result = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy,
            availableInspection(
                anchor(),
                observed(
                    "bi_db_consumer",
                    "bi_aggregate_command_queue",
                    BiObjectKind.QUEUE,
                    "bi.aggregate",
                ),
                observed(
                    "bi_db_consumer",
                    "bi_aggregate_state_queue",
                    BiObjectKind.QUEUE,
                    "bi.aggregate",
                ),
            ),
        )

        result.script.assert()
            .contains(
                "DROP VIEW IF EXISTS \"bi_db_consumer\".\"bi_aggregate_command_consumer\"",
                "DROP VIEW IF EXISTS \"bi_db_consumer\".\"bi_aggregate_state_consumer\"",
            )
            .doesNotContain(
                "DROP TABLE IF EXISTS \"bi_db_consumer\".\"bi_aggregate_command_queue\"",
                "DROP TABLE IF EXISTS \"bi_db_consumer\".\"bi_aggregate_state_queue\"",
                "CREATE TABLE \"bi_db_consumer\".\"bi_aggregate_command_queue\"",
                "CREATE TABLE \"bi_db_consumer\".\"bi_aggregate_state_queue\"",
                "CREATE TABLE IF NOT EXISTS \"bi_db_consumer\".\"bi_aggregate_command_queue\"",
                "CREATE TABLE IF NOT EXISTS \"bi_db_consumer\".\"bi_aggregate_state_queue\"",
            )
    }

    @Test
    fun `should create an authoritatively missing Kafka queue without an existence guard`() {
        val result = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy,
            availableInspection(anchor()),
        )

        result.script.assert()
            .contains(
                "CREATE TABLE \"bi_db_consumer\".\"bi_aggregate_command_queue\"",
                "CREATE TABLE \"bi_db_consumer\".\"bi_aggregate_state_queue\"",
            )
            .doesNotContain(
                "CREATE TABLE IF NOT EXISTS \"bi_db_consumer\".\"bi_aggregate_command_queue\"",
                "CREATE TABLE IF NOT EXISTS \"bi_db_consumer\".\"bi_aggregate_state_queue\"",
            )
    }

    @Test
    fun `should reconcile stale observed views without dropping stores`() {
        val inspection = availableInspection(
            anchor(),
            observed(
                database = "bi_db",
                name = "bi_aggregate_state_last_root_removed",
                kind = BiObjectKind.VIEW,
                aggregate = "bi-service.aggregate",
            ),
            observed(
                database = "bi_db",
                name = "bi_removed_command_store",
                kind = BiObjectKind.STORE,
                aggregate = "bi-service.removed",
            ),
        )

        val reconciled = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy,
            inspection,
        )

        reconciled.script.assert().contains(
            "DROP VIEW IF EXISTS \"bi_db\".\"bi_aggregate_state_last_root_removed\""
        )
        reconciled.script.assert()
            .doesNotContain("DROP TABLE IF EXISTS \"bi_db\".\"bi_removed_command_store\"")
        reconciled.diagnostics.single { it.code == BiScriptDiagnosticCode.ORPHANED_DATA_TABLE }
            .aggregate.assert().isEqualTo("bi-service.removed")
    }

    @Test
    fun `should delete every observed owned object during reset`() {
        val inspection = availableInspection(
            anchor(),
            observed("bi_db", "bi_sibling_command_store", BiObjectKind.STORE, "bi-service.sibling"),
            observed(
                "bi_db_consumer",
                "bi_sibling_command_queue",
                BiObjectKind.QUEUE,
                "bi-service.sibling",
            ),
        )
        val result = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Reset(true),
            inspection,
        )

        result.script.assert().contains(
            "DROP TABLE IF EXISTS \"bi_db_consumer\".\"bi_sibling_command_queue\"",
            "DROP TABLE IF EXISTS \"bi_db\".\"bi_sibling_command_store\"",
        )
    }

    @Test
    fun `should delete registry owned objects whose catalog comment is missing during reset`() {
        val options = BiScriptOptions(consumerGroupNamespace = "test")
        val descriptor = BiDeploymentDescriptor.from(options)
        val identity = BiConsumerIdentity.deterministic(descriptor)
        val key = BiObjectKey("bi_db", "bi_sibling_command_store")
        val registry = BiOwnershipRegistry.empty(descriptor.deploymentId)
            .beginCreate(
                BiOwnershipRegistration(
                    key = key,
                    kind = BiObjectKind.STORE,
                    aggregate = "bi-service.sibling",
                    consumerIdentity = identity.value,
                    definitionFingerprint = "a".repeat(32),
                )
            ).markMutationVerified(key)
        val inspection = BiDeploymentInspection.Available.reconciled(
            deployment = ObservedBiDeployment(
                listOf(
                    anchor(options = options, identity = identity),
                    ObservedBiObject(
                        database = key.database,
                        name = key.name,
                        engine = "ReplacingMergeTree",
                    ),
                )
            ),
            repairableComputedDrifts = emptyList(),
            ownershipRegistry = registry,
        )

        val result = generator(options).generate(
            setOf(aggregate),
            BiScriptOperation.Reset(true),
            inspection,
        )

        result.script.assert().contains(
            "DROP TABLE IF EXISTS \"${key.database}\".\"${key.name}\""
        )
    }

    @Test
    fun `should drop the ownership registry after persisting reset intent`() {
        val options = BiScriptOptions(consumerGroupNamespace = "test")
        val descriptor = BiDeploymentDescriptor.from(options)
        val registry = BiOwnershipRegistry.empty(descriptor.deploymentId)
        val inspection = BiDeploymentInspection.Available.reconciled(
            deployment = ObservedBiDeployment(listOf(anchor(options = options))),
            repairableComputedDrifts = emptyList(),
            ownershipRegistry = registry,
        )

        val result = generator(options).generate(
            setOf(aggregate),
            BiScriptOperation.Reset(true),
            inspection,
        )

        val resetIntent = result.script.indexOf("deployment-reset-intent")
        val registryDrop = result.script.indexOf(
            "DROP TABLE IF EXISTS \"${options.consumerDatabase}\".\"${registry.name}\""
        )
        resetIntent.assert().isLessThan(registryDrop)
        registryDrop.assert().isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `should reuse stable identity for deploy and create a new reset identity`() {
        val identity = BiConsumerIdentity("1".repeat(32))
        val inspection = availableInspection(anchor(identity = identity))

        val deploy = generator().generate(setOf(aggregate), BiScriptOperation.Deploy, inspection)
        val reset = generator().generate(
            setOf(aggregate),
            BiScriptOperation.Reset(true),
            inspection,
        )

        deploy.script.assert().contains("wow-bi.${identity.value}.bi_aggregate_command_consumer")
        reset.script.assert().doesNotContain("wow-bi.${identity.value}.bi_aggregate_command_consumer")
    }

    @Test
    fun `should reuse resetting identity while cleaning residual objects from the prior epoch`() {
        val oldIdentity = BiConsumerIdentity("1".repeat(32))
        val resetIdentity = BiConsumerIdentity("2".repeat(32))
        val inspection = availableInspection(
            anchor(identity = resetIdentity, phase = BiDeploymentPhase.RESETTING),
            observed(
                database = "bi_db",
                name = "bi_legacy_command_store",
                kind = BiObjectKind.STORE,
                aggregate = "bi-service.legacy",
                identity = oldIdentity,
            ),
        )

        val retry = generator().generate(setOf(aggregate), BiScriptOperation.Reset(true), inspection)

        retry.script.assert()
            .contains(
                "wow-bi.${resetIdentity.value}.bi_aggregate_command_consumer",
                "\"phase\":\"RESETTING\"",
                "\"phase\":\"STABLE\"",
                "DROP TABLE IF EXISTS \"bi_db\".\"bi_legacy_command_store\"",
            )
            .doesNotContain("wow-bi.${oldIdentity.value}.bi_aggregate_command_consumer")
    }

    @Test
    fun `should reject deploy while reset is in progress`() {
        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy,
                availableInspection(anchor(phase = BiDeploymentPhase.RESETTING)),
            )
        }.message.assert().contains("RESETTING", "retry RESET")
    }

    @Test
    fun `should reject a non-canonical deployment anchor`() {
        val rogueAnchor = observed(
            database = "bi_db_consumer",
            name = "rogue_deployment_anchor",
            kind = BiObjectKind.ANCHOR,
            aggregate = null,
            phase = BiDeploymentPhase.RESETTING,
        )

        listOf(BiScriptOperation.Deploy, BiScriptOperation.Reset(true)).forEach { operation ->
            assertThrows<IllegalArgumentException> {
                generator().generate(setOf(aggregate), operation, availableInspection(rogueAnchor))
            }.message.assert().contains("anchor", "canonical", "rogue_deployment_anchor")
        }
    }

    @Test
    fun `should reject multiple deployment anchors before choosing a canonical anchor`() {
        val canonicalAnchor = anchor()
        val duplicateAnchor = canonicalAnchor.copy(name = "rogue_deployment_anchor")

        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy,
                availableInspection(canonicalAnchor, duplicateAnchor),
            )
        }.message.assert().contains(
            "multiple deployment anchors",
            "__wow_bi_deployment",
            "rogue_deployment_anchor",
        )
    }

    @Test
    fun `should reset an identified empty scope and require a durable anchor namespace`() {
        val staleStore = observed(
            database = "bi_db",
            name = "legacy_state_store",
            kind = BiObjectKind.STORE,
            aggregate = "legacy.aggregate",
        )

        val reset = generator().generate(
            emptySet(),
            BiScriptOperation.Reset(true),
            availableInspection(anchor(), staleStore),
        )

        reset.script.assert()
            .contains(
                "\"phase\":\"RESETTING\"",
                "DROP TABLE IF EXISTS \"bi_db\".\"legacy_state_store\"",
                "\"phase\":\"STABLE\"",
            )
            .doesNotContain("ENGINE = Kafka", "_command_queue", "_state_queue")
        reset.statements.filter { statement -> statement.contains("__wow_bi_deployment") }
            .assert().hasSize(2)

        assertThrows<IllegalArgumentException> {
            BiScriptGenerator().generate(
                emptySet(),
                BiScriptOperation.Reset(true),
                availableInspection(),
            )
        }.message.assert().contains("consumerGroupNamespace", "before RESET")
    }

    @Test
    fun `should reject a canonical anchor with an incompatible engine`() {
        val invalidAnchor = anchor().copy(engine = "MergeTree")

        listOf(BiScriptOperation.Deploy, BiScriptOperation.Reset(true)).forEach { operation ->
            assertThrows<IllegalArgumentException> {
                generator().generate(setOf(aggregate), operation, availableInspection(invalidAnchor))
            }.message.assert().contains("must use the View engine")
        }
    }

    @Test
    fun `should reject owned catalog objects whose kind is incompatible with the physical engine`() {
        val incompatibleObjects = listOf(
            observed(
                database = "bi_db",
                name = "bi_aggregate_command_store",
                kind = BiObjectKind.STORE,
                aggregate = "bi.aggregate",
            ).copy(engine = "View"),
            observed(
                database = "bi_db",
                name = "bi_aggregate_command",
                kind = BiObjectKind.VIEW,
                aggregate = "bi.aggregate",
            ).copy(engine = "MergeTree"),
            observed(
                database = "bi_db_consumer",
                name = "bi_aggregate_command_queue",
                kind = BiObjectKind.QUEUE,
                aggregate = "bi.aggregate",
            ).copy(engine = "View"),
            observed(
                database = "bi_db_consumer",
                name = "bi_aggregate_command_consumer",
                kind = BiObjectKind.CONSUMER,
                aggregate = "bi.aggregate",
            ).copy(engine = "View"),
            observed(
                database = "bi_db",
                name = "removed_state_store",
                kind = BiObjectKind.STORE,
                aggregate = "bi-service.removed",
            ).copy(engine = "View"),
        )

        incompatibleObjects.forEach { incompatible ->
            assertThrows<IllegalArgumentException> {
                generator().generate(
                    setOf(aggregate),
                    BiScriptOperation.Deploy,
                    availableInspection(incompatible),
                )
            }.message.assert().contains("incompatible", "engine")
        }
    }

    @Test
    fun `should validate and reconcile a stale consumer by its physical engine`() {
        val staleConsumer = observed(
            database = "bi_db_consumer",
            name = "bi_removed_command_consumer",
            kind = BiObjectKind.CONSUMER,
            aggregate = "bi-service.removed",
        )

        generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy,
            availableInspection(anchor(), staleConsumer.copy(engine = "MaterializedView")),
        ).script.assert().contains(
            "DROP VIEW IF EXISTS \"bi_db_consumer\".\"bi_removed_command_consumer\""
        )

        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy,
                availableInspection(anchor(), staleConsumer.copy(engine = "View")),
            )
        }.message.assert().contains("incompatible engine", "bi_removed_command_consumer")
    }

    @Test
    fun `should reconcile an owned stale queue without touching an unowned catalog object`() {
        val staleQueue = observed(
            database = "bi_db_consumer",
            name = "bi_removed_command_queue",
            kind = BiObjectKind.QUEUE,
            aggregate = "bi-service.removed",
        )
        val userTable = ObservedBiObject(
            database = "bi_db",
            name = "user_managed_table",
            engine = "MergeTree",
        )

        generator().generate(
            setOf(aggregate),
            BiScriptOperation.Deploy,
            availableInspection(anchor(), staleQueue, userTable),
        ).script.assert()
            .contains("DROP TABLE IF EXISTS \"bi_db_consumer\".\"bi_removed_command_queue\"")
            .doesNotContain("user_managed_table")
    }

    @Test
    fun `should reject a desired cluster store with the wrong store engine`() {
        val distributedStoreUsingLocalEngine = observed(
            database = "bi_db",
            name = "bi_aggregate_command_store",
            kind = BiObjectKind.STORE,
            aggregate = "bi.aggregate",
        ).copy(engine = "ReplicatedReplacingMergeTree")

        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy,
                availableInspection(distributedStoreUsingLocalEngine),
            )
        }.message.assert().contains("expected engine", "Distributed")
    }

    @Test
    fun `should reject resetting retry with a different configuration`() {
        val currentOptions = BiScriptOptions(consumerGroupNamespace = "test")
        val changedOptions = currentOptions.copy(kafkaBootstrapServers = "changed-kafka:9092")
        val inspection = availableInspection(anchor(currentOptions, phase = BiDeploymentPhase.RESETTING))

        assertThrows<IllegalArgumentException> {
            generator(changedOptions).generate(setOf(aggregate), BiScriptOperation.Reset(true), inspection)
        }.message.assert().contains("RESETTING", "configuration")
    }

    @Test
    fun `should reject mixed or missing observed consumer identities`() {
        val firstIdentity = BiConsumerIdentity("1".repeat(32))
        val secondIdentity = BiConsumerIdentity("2".repeat(32))
        val mixedInspection = availableInspection(
            anchor(identity = firstIdentity),
            observed(
                database = "bi_db",
                name = "legacy_view",
                kind = BiObjectKind.VIEW,
                aggregate = "bi-service.legacy",
                identity = secondIdentity,
            ),
        )

        assertThrows<IllegalArgumentException> {
            generator().generate(setOf(aggregate), BiScriptOperation.Deploy, mixedInspection)
        }.message.assert().contains("mixed consumer identities")

        val descriptor = BiDeploymentDescriptor.from(BiScriptOptions(consumerGroupNamespace = "test"))
        val missingIdentityInspection = availableInspection(
            ObservedBiObject(
                database = "bi_db_consumer",
                name = "__wow_bi_deployment",
                engine = "View",
                metadata = BiObjectMetadata(
                    deploymentId = descriptor.deploymentId,
                    configurationFingerprint = descriptor.configurationFingerprint,
                    topologyFingerprint = descriptor.topologyFingerprint,
                    kind = BiObjectKind.ANCHOR,
                ),
            )
        )

        assertThrows<IllegalArgumentException> {
            generator().generate(setOf(aggregate), BiScriptOperation.Deploy, missingIdentityInspection)
        }.message.assert().contains("missing its consumer identity anchor")
    }

    @Test
    fun `should reject desired objects with inconsistent ownership metadata`() {
        val inconsistentObjects = listOf(
            observed(
                database = "bi_db_consumer",
                name = "bi_aggregate_command_queue",
                kind = BiObjectKind.VIEW,
                aggregate = "bi-service.aggregate",
            ),
            observed(
                database = "bi_db_consumer",
                name = "bi_aggregate_command_queue",
                kind = BiObjectKind.QUEUE,
                aggregate = "bi-service.another",
            ),
        )

        inconsistentObjects.forEach { inconsistent ->
            assertThrows<IllegalArgumentException> {
                generator().generate(
                    setOf(aggregate),
                    BiScriptOperation.Deploy,
                    availableInspection(inconsistent),
                )
            }.message.assert().contains("has inconsistent ownership metadata")
        }
    }

    @Test
    fun `should fail closed when a desired object is foreign`() {
        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy,
                availableInspection(
                    ObservedBiObject(
                        database = "bi_db_consumer",
                        name = "bi_aggregate_command_queue",
                        engine = "Kafka",
                    )
                ),
            )
        }.message.assert().contains("foreign catalog object")
    }

    @Test
    fun `should require reset when observed deployment configuration changed`() {
        val changedOptions = BiScriptOptions(
            consumerGroupNamespace = "test",
            kafkaBootstrapServers = "changed-kafka:9092",
        )
        val inspection = availableInspection(anchor())

        assertThrows<IllegalArgumentException> {
            generator(changedOptions).generate(setOf(aggregate), BiScriptOperation.Deploy, inspection)
        }.message.assert().contains("use RESET")

        generator(changedOptions).generate(
            setOf(aggregate),
            BiScriptOperation.Reset(true),
            inspection,
        ).script.assert().contains("changed-kafka:9092")
    }

    @Test
    fun `should reject topology changes through deploy or reset`() {
        val currentOptions = BiScriptOptions(
            consumerGroupNamespace = "test",
            topology = ClickHouseTopology.Cluster(name = "current-cluster", installation = "current-installation"),
        )
        val inspection = availableInspection(anchor(currentOptions))
        val changedTopologies = listOf(
            ClickHouseTopology.Standalone,
            ClickHouseTopology.Cluster(name = "other-cluster", installation = "current-installation"),
            ClickHouseTopology.Cluster(name = "current-cluster", installation = "other-installation"),
        )

        changedTopologies.forEach { topology ->
            val changedOptions = currentOptions.copy(topology = topology)
            listOf(BiScriptOperation.Deploy, BiScriptOperation.Reset(true)).forEach { operation ->
                assertThrows<IllegalArgumentException> {
                    generator(changedOptions).generate(setOf(aggregate), operation, inspection)
                }.message.assert().contains("topology", "cannot be changed")
            }
        }
    }

    @Test
    fun `should reject mixed topology fingerprints in one observed deployment`() {
        val mixedStore = observed(
            database = "bi_db",
            name = "example_order_state_store",
            kind = BiObjectKind.STORE,
            aggregate = "example.order",
        ).let { observed ->
            observed.copy(metadata = observed.metadata!!.copy(topologyFingerprint = "f".repeat(32)))
        }

        assertThrows<IllegalArgumentException> {
            generator().generate(
                setOf(aggregate),
                BiScriptOperation.Deploy,
                availableInspection(anchor(), mixedStore),
            )
        }.message.assert().contains("mixed topology fingerprints")
    }

    @Test
    fun `should never delete catalog objects owned by another deployment`() {
        val foreignOptions = BiScriptOptions(
            database = "bi_db",
            consumerDatabase = "bi_db_consumer",
            consumerGroupNamespace = "another-deployment",
        )
        val foreignStore = observed(
            database = "bi_db",
            name = "foreign_command_store",
            kind = BiObjectKind.STORE,
            aggregate = "foreign.aggregate",
            options = foreignOptions,
        )

        generator().generate(
            setOf(aggregate),
            BiScriptOperation.Reset(true),
            availableInspection(foreignStore),
        ).script.assert().doesNotContain("foreign_command_store")
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
        forward.script.indexOf("-- bi.aggregate.commandStorage --")
            .assert()
            .isLessThan(forward.script.indexOf("-- bi.sibling.commandStorage --"))
    }

    @Test
    fun `should reject duplicate aggregate inputs that resolve to the same BI object names`() {
        val duplicate = object : NamedAggregateDecorator {
            override val namedAggregate: NamedAggregate = aggregate
        }

        assertThrows<IllegalArgumentException> {
            generator().generate(linkedSetOf(aggregate, duplicate))
        }.message.assert().contains("BI object name collision", "bi_aggregate_command_store")
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
            "lifecycle.inspection",
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
    fun `should generate global state without a deployment anchor for an unidentified empty scope`() {
        val result = BiScriptGenerator().generate(emptySet())

        result.script.assert().contains("-- global --")
        result.script.assert().contains("CREATE DATABASE IF NOT EXISTS")
        result.script.assert().contains("-- lifecycle --")
        result.script.assert().doesNotContain("-- deployment-anchor --", "__wow_bi_deployment", ".command --")
        result.diagnostics.map(BiScriptDiagnostic::code)
            .assert().containsExactly(BiScriptDiagnosticCode.INSPECTION_UNAVAILABLE)
    }

    @Test
    fun `should preserve the deployment anchor for an identified empty scope`() {
        generator().generate(emptySet()).script.assert()
            .contains("-- deployment-anchor --", "__wow_bi_deployment")
            .doesNotContain(".command --")
    }

    @Test
    fun `should reconcile a previously owned anchor for an unidentified empty scope`() {
        val options = BiScriptOptions()

        val result = BiScriptGenerator(options).generate(
            emptySet(),
            inspection = availableInspection(anchor(options)),
        )

        result.script.assert()
            .contains(
                "-- reconcile-observed-catalog --",
                "DROP VIEW IF EXISTS \"bi_db_consumer\".\"__wow_bi_deployment\"",
            )
            .doesNotContain("-- deployment-anchor --", "CREATE OR REPLACE VIEW")
    }

    @Test
    fun `should reject an unidentified empty anchor when a different deployment starts`() {
        val legacyOptions = BiScriptOptions()
        val currentOptions = BiScriptOptions(consumerGroupNamespace = "test")

        listOf(BiScriptOperation.Deploy, BiScriptOperation.Reset(true)).forEach { operation ->
            assertThrows<IllegalArgumentException> {
                generator(currentOptions).generate(
                    setOf(aggregate),
                    operation = operation,
                    inspection = availableInspection(anchor(legacyOptions)),
                )
            }.message.assert().contains("occupied by a foreign catalog object")
        }
    }

    @Test
    fun `should require a consumer group namespace only when aggregates create Kafka consumers`() {
        assertThrows<IllegalArgumentException> {
            BiScriptGenerator().generate(setOf(aggregate))
        }.message.assert()
            .isEqualTo("consumerGroupNamespace must be configured before generating BI Kafka consumers")

        BiScriptGenerator().generate(emptySet()).destructive.assert().isFalse()
    }

    private fun namedAggregate(name: String): NamedAggregate =
        MetadataSearcher.localAggregates.single { it.aggregateName == name }

    private fun generator(
        options: BiScriptOptions = BiScriptOptions(consumerGroupNamespace = "test"),
    ): BiScriptGenerator = BiScriptGenerator(options)

    private fun availableInspection(vararg objects: ObservedBiObject): BiDeploymentInspection.Available =
        BiDeploymentInspection.Available(ObservedBiDeployment(objects.toList()))

    private fun anchor(
        options: BiScriptOptions = BiScriptOptions(consumerGroupNamespace = "test"),
        identity: BiConsumerIdentity = BiConsumerIdentity.deterministic(BiDeploymentDescriptor.from(options)),
        phase: BiDeploymentPhase = BiDeploymentPhase.STABLE,
    ): ObservedBiObject = observed(
        database = options.consumerDatabase,
        name = "__wow_bi_deployment",
        kind = BiObjectKind.ANCHOR,
        aggregate = null,
        options = options,
        identity = identity,
        phase = phase,
    )

    private fun observed(
        database: String,
        name: String,
        kind: BiObjectKind,
        aggregate: String?,
        options: BiScriptOptions = BiScriptOptions(consumerGroupNamespace = "test"),
        identity: BiConsumerIdentity = BiConsumerIdentity.deterministic(BiDeploymentDescriptor.from(options)),
        phase: BiDeploymentPhase = BiDeploymentPhase.STABLE,
    ): ObservedBiObject {
        val descriptor = BiDeploymentDescriptor.from(options)
        return ObservedBiObject(
            database = database,
            name = name,
            engine = when (kind) {
                BiObjectKind.QUEUE -> "Kafka"
                BiObjectKind.STORE -> "ReplacingMergeTree"
                else -> "View"
            },
            metadata = BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                configurationFingerprint = descriptor.configurationFingerprint,
                topologyFingerprint = descriptor.topologyFingerprint,
                phase = phase,
                aggregate = aggregate,
                kind = kind,
                consumerIdentity = identity.value,
            ),
        )
    }

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

    private fun indexOfAnchorStatement(statements: List<String>, phase: BiDeploymentPhase): Int =
        statements.indexOfFirst { statement ->
            statement.contains("__wow_bi_deployment") && statement.contains("\"phase\":\"$phase\"")
        }.also { index ->
            check(index >= 0) { "Deployment anchor in phase [$phase] was not generated." }
        }
}
