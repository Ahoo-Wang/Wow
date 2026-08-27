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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ClickHouseOwnershipRegistryInspectionTest {
    @Test
    fun `should use exact registry keys without ownership prefix discovery`() {
        val fixture = RegistryInspectionFixture()

        val snapshot = ClickHouseCatalogReader(fixture.client).read(fixture.request)

        snapshot.ownershipRegistry?.entries?.single()?.key.assert().isEqualTo(fixture.registeredKey)
        snapshot.objects.map(ClickHouseCatalogObject::key).assert().containsExactly(fixture.registeredKey)
        fixture.queries.single { query -> query.columns == CATALOG_COLUMNS }.sql.assert()
            .contains("name IN {databaseTables:Array(String)}")
            .doesNotContain("startsWith(comment", "ownershipPrefix")
    }

    @Test
    fun `deploy should reject an active registry entry whose catalog object is missing`() {
        val fixture = RegistryInspectionFixture(includeRegisteredObject = false)

        assertThrows<IllegalStateException> {
            ClickHouseCatalogReader(fixture.client).read(fixture.request)
        }.message.assert().contains("missing ACTIVE/RETIRED", fixture.registeredKey.name)
    }

    @Test
    fun `reset should retain registry ownership when an active catalog object is missing`() {
        val fixture = RegistryInspectionFixture(includeRegisteredObject = false)

        val snapshot = ClickHouseCatalogReader(fixture.client).read(
            fixture.request.copy(operation = BiScriptOperation.Reset(true))
        )

        snapshot.ownershipRegistry?.entries?.single()?.key.assert().isEqualTo(fixture.registeredKey)
        snapshot.objects.assert().isEmpty()
    }

    @Test
    fun `should reject an ownership registry with an incompatible engine`() {
        val fixture = RegistryInspectionFixture(registryEngine = "Memory")

        assertThrows<IllegalStateException> {
            ClickHouseCatalogReader(fixture.client).read(fixture.request)
        }.message.assert().contains("ownership registry", "ReplacingMergeTree")
    }

    @Test
    fun `should reject conflicting registry heads at the same revision`() {
        val fixture = RegistryInspectionFixture(conflictingHead = true)

        assertThrows<IllegalStateException> {
            ClickHouseCatalogReader(fixture.client).read(fixture.request)
        }.message.assert().contains("conflicting HEAD")
    }

    @Test
    fun `should reject conflicting registry object rows at the same revision`() {
        val fixture = RegistryInspectionFixture(conflictingEntry = true)

        assertThrows<IllegalStateException> {
            ClickHouseCatalogReader(fixture.client).read(fixture.request)
        }.message.assert().contains("conflicting object rows", fixture.registeredKey.name)
    }

    private class RegistryInspectionFixture(
        includeRegisteredObject: Boolean = true,
        private val conflictingHead: Boolean = false,
        private val conflictingEntry: Boolean = false,
        private val registryEngine: String = "ReplacingMergeTree",
    ) {
        val options = BiScriptOptions(
            consumerGroupNamespace = "orders",
            topology = ClickHouseTopology.Standalone,
        )
        private val descriptor = BiDeploymentDescriptor.from(options)
        val registeredKey = BiObjectKey(options.database, "orders_state_store")
        private val registry = BiOwnershipRegistry.empty(descriptor.deploymentId)
            .beginCreate(
                BiOwnershipRegistration(
                    key = registeredKey,
                    kind = BiObjectKind.STORE,
                    aggregate = "orders.order",
                    consumerIdentity = "b".repeat(32),
                    definitionFingerprint = "c".repeat(32),
                )
            )
            .markMutationVerified(registeredKey)
        val queries = mutableListOf<RecordedQuery>()
        val client = object : ClickHouseCatalogClient {
            @Suppress("LongMethod") // One fake client response matrix models the ordered registry read protocol.
            override fun query(
                sql: String,
                parameters: Map<String, Any>,
                columns: List<String>,
            ): List<ClickHouseCatalogRecord> {
                queries += RecordedQuery(sql, parameters, columns)
                return when (columns) {
                    REGISTRY_TABLE_COLUMNS -> listOf(
                        record(
                            "database" to options.consumerDatabase,
                            "name" to registry.name,
                            "engine" to registryEngine,
                            "engine_full" to "$registryEngine(revision)",
                            "comment" to "wow-bi-registry:${descriptor.deploymentId}",
                            "sorting_key" to
                                "deployment_id, row_kind, object_database, object_name",
                        )
                    )

                    COLUMN_COLUMNS -> REGISTRY_SCHEMA.mapIndexed { index, (name, type) ->
                        record(
                            "database" to options.consumerDatabase,
                            "table" to registry.name,
                            "name" to name,
                            "type" to type,
                            "position" to (index + 1).toString(),
                        )
                    }

                    REGISTRY_HEAD_COLUMNS -> buildList {
                        add(
                            record(
                                "revision" to registry.revision.toString(),
                                "snapshot_fingerprint" to registry.snapshotFingerprint(),
                                "row_fingerprint" to registry.snapshotFingerprint(),
                            )
                        )
                        if (conflictingHead) {
                            add(
                                record(
                                    "revision" to registry.revision.toString(),
                                    "snapshot_fingerprint" to "d".repeat(32),
                                    "row_fingerprint" to "d".repeat(32),
                                )
                            )
                        }
                    }

                    REGISTRY_COLUMNS -> buildList {
                        registry.entries.forEach { entry ->
                            add(
                                record(
                                    "object_database" to entry.key.database,
                                    "object_name" to entry.key.name,
                                    "kind" to entry.kind.name,
                                    "aggregate" to entry.aggregate,
                                    "consumer_identity" to entry.consumerIdentity,
                                    "definition_fingerprint" to entry.definitionFingerprint,
                                    "revision" to entry.revision.toString(),
                                    "status" to entry.status.name,
                                    "row_fingerprint" to entry.rowFingerprint(),
                                )
                            )
                            if (conflictingEntry) {
                                add(
                                    record(
                                        "object_database" to entry.key.database,
                                        "object_name" to entry.key.name,
                                        "kind" to entry.kind.name,
                                        "aggregate" to entry.aggregate,
                                        "consumer_identity" to entry.consumerIdentity,
                                        "definition_fingerprint" to "e".repeat(32),
                                        "revision" to entry.revision.toString(),
                                        "status" to entry.status.name,
                                        "row_fingerprint" to "e".repeat(32),
                                    )
                                )
                            }
                        }
                    }

                    CATALOG_COLUMNS -> if (includeRegisteredObject) {
                        listOf(
                            record(
                                "database" to registeredKey.database,
                                "name" to registeredKey.name,
                                "engine" to "ReplacingMergeTree",
                                "engine_full" to "ReplacingMergeTree(version)",
                                "create_table_query" to "CREATE TABLE ${registeredKey.name}",
                                "as_select" to "",
                                "comment" to BiObjectMetadataCodec.encode(
                                    BiObjectMetadata(
                                        deploymentId = descriptor.deploymentId,
                                        configurationFingerprint = descriptor.configurationFingerprint,
                                        topologyFingerprint = descriptor.topologyFingerprint,
                                        aggregate = "orders.order",
                                        kind = BiObjectKind.STORE,
                                        consumerIdentity = "b".repeat(32),
                                    )
                                ),
                                "partition_key" to "toYYYYMM(create_time)",
                                "sorting_key" to "(tenant_id, aggregate_id, version)",
                            )
                        )
                    } else {
                        emptyList()
                    }

                    else -> error("Unexpected columns: $columns")
                }
            }

            override fun close() = Unit
        }
        val request = ClickHouseCatalogReadRequest(
            options = options,
            operation = BiScriptOperation.Deploy,
            desiredObjectKeys = setOf(registeredKey),
            desiredObjects = null,
            cancellation = ClickHouseQueryCancellation(),
        )
    }

    private data class RecordedQuery(
        val sql: String,
        val parameters: Map<String, Any>,
        val columns: List<String>,
    )

    companion object {
        private val REGISTRY_TABLE_COLUMNS = listOf(
            "database",
            "name",
            "engine",
            "engine_full",
            "comment",
            "sorting_key",
        )
        private val COLUMN_COLUMNS = listOf("database", "table", "name", "type", "position")
        private val REGISTRY_SCHEMA = listOf(
            "deployment_id" to "FixedString(32)",
            "row_kind" to "LowCardinality(String)",
            "object_database" to "String",
            "object_name" to "String",
            "kind" to "LowCardinality(String)",
            "aggregate" to "Nullable(String)",
            "consumer_identity" to "Nullable(FixedString(32))",
            "definition_fingerprint" to "FixedString(32)",
            "revision" to "UInt64",
            "status" to "LowCardinality(String)",
            "row_fingerprint" to "FixedString(32)",
            "recorded_at" to "DateTime64(3, 'UTC')",
        )
        private val REGISTRY_HEAD_COLUMNS = listOf(
            "revision",
            "snapshot_fingerprint",
            "row_fingerprint",
        )
        private val REGISTRY_COLUMNS = listOf(
            "object_database",
            "object_name",
            "kind",
            "aggregate",
            "consumer_identity",
            "definition_fingerprint",
            "revision",
            "status",
            "row_fingerprint",
        )
        private val CATALOG_COLUMNS = listOf(
            "database",
            "name",
            "engine",
            "engine_full",
            "create_table_query",
            "as_select",
            "comment",
            "partition_key",
            "sorting_key",
        )

        private fun record(vararg values: Pair<String, String?>): ClickHouseCatalogRecord =
            ClickHouseCatalogRecord(mapOf(*values))
    }
}
