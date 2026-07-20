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

import me.ahoo.wow.bi.renderer.ClickHouseScriptRenderer
import me.ahoo.wow.serialization.JsonSerializer

private const val DATABASE_TABLES_PREDICATE = "__DATABASE_TABLES_PREDICATE__"
private const val CONSUMER_DATABASE_TABLES_PREDICATE = "__CONSUMER_DATABASE_TABLES_PREDICATE__"
private const val TABLES_PREDICATE = "__TABLES_PREDICATE__"
private const val EXPECTED_DEFINITIONS_EXPRESSION = "__EXPECTED_DEFINITIONS_EXPRESSION__"

internal class ClickHouseCatalogReader(private val catalogClient: ClickHouseCatalogClient) {
    fun read(request: ClickHouseCatalogReadRequest): ClickHouseCatalogSnapshot = with(request) {
        val registry = OwnershipRegistryReader(catalogClient).read(options, cancellation)
        val registryKeys = registry?.entries
            ?.mapTo(linkedSetOf(), BiOwnershipRegistryEntry::key)
            .orEmpty()
        val anchorKey = BiObjectKey(options.consumerDatabase, ClickHouseScriptRenderer.DEPLOYMENT_ANCHOR)
        val effectiveKeys = when {
            registry != null -> desiredObjectKeys.orEmpty() + registryKeys + anchorKey
            else -> desiredObjectKeys
        }
        val objects = when (val topology = options.topology) {
            ClickHouseTopology.Standalone -> readStandalone(
                StandaloneReadContext(options, operation, cancellation),
                effectiveKeys,
                registry != null,
            )
            is ClickHouseTopology.Cluster -> readCluster(
                ClusterReadContext(options, topology, operation, cancellation),
                effectiveKeys,
                registry != null,
            )
        }
        validateRegistryAnchor(registry, objects, operation)
        registry?.validateCatalogPresence(objects, operation)
        return ClickHouseCatalogSnapshot(
            objects = objects,
            expectedQueries = canonicalizeExpectedQueries(request, objects),
            ownershipRegistry = registry,
        )
    }

    private class OwnershipRegistryReader(private val catalogClient: ClickHouseCatalogClient) {
        fun read(
            options: BiScriptOptions,
            cancellation: ClickHouseQueryCancellation,
        ): BiOwnershipRegistry? = when (val topology = options.topology) {
            ClickHouseTopology.Standalone -> readStandaloneOwnershipRegistry(options, cancellation)
            is ClickHouseTopology.Cluster -> readClusterOwnershipRegistry(options, topology, cancellation)
        }

        private fun readStandaloneOwnershipRegistry(
            options: BiScriptOptions,
            cancellation: ClickHouseQueryCancellation,
        ): BiOwnershipRegistry? {
            val deploymentId = BiDeploymentDescriptor.from(options).deploymentId
            val registryName = BiOwnershipRegistry.empty(deploymentId).name
            val registryKey = BiObjectKey(options.consumerDatabase, registryName)
            val registryParameters = options.catalogParameters() + mapOf(
                "registryDatabase" to options.consumerDatabase,
                "registryTable" to registryName,
            )
            val registryTables = catalogClient.query(
                sql = REGISTRY_TABLE_QUERY,
                parameters = registryParameters,
                columns = REGISTRY_TABLE_COLUMNS,
                cancellation = cancellation,
            ).filter { record -> record.toObjectKey() == registryKey }
                .map(ClickHouseCatalogRecord::toRegistryTableDefinition)
            if (registryTables.isEmpty()) {
                return null
            }
            check(registryTables.size == 1) {
                "ClickHouse BI ownership registry [${registryKey.database}.${registryKey.name}] is duplicated"
            }
            val registryColumns = catalogClient.query(
                sql = REGISTRY_COLUMNS_QUERY,
                parameters = registryParameters,
                columns = COLUMN_COLUMNS,
                cancellation = cancellation,
            ).map(ClickHouseCatalogRecord::toCatalogColumn)
            ClickHouseOwnershipRegistryShapeValidator.validate(
                topology = options.topology,
                deploymentId = deploymentId,
                table = registryTables.single(),
                columns = registryColumns,
            )
            val heads = catalogClient.query(
                sql = REGISTRY_HEAD_QUERY,
                parameters = registryParameters + ("deploymentId" to deploymentId),
                columns = REGISTRY_HEAD_COLUMNS,
                cancellation = cancellation,
            ).map(ClickHouseCatalogRecord::toRegistryHead)
            val head = resolveRegistryHead(heads) ?: return null
            val rows = catalogClient.query(
                sql = REGISTRY_ENTRIES_QUERY,
                parameters = registryParameters + ("deploymentId" to deploymentId),
                columns = REGISTRY_COLUMNS,
                cancellation = cancellation,
            ).map(ClickHouseCatalogRecord::toRegistryRow)
            return restoreRegistry(deploymentId, head, rows)
        }

        private fun readClusterOwnershipRegistry(
            options: BiScriptOptions,
            topology: ClickHouseTopology.Cluster,
            cancellation: ClickHouseQueryCancellation,
        ): BiOwnershipRegistry? {
            val deploymentId = BiDeploymentDescriptor.from(options).deploymentId
            val registryName = BiOwnershipRegistry.empty(deploymentId).name
            val clusterParameters = mapOf("cluster" to topology.name)
            val context = ClusterRegistryReadContext(
                topology = topology,
                cancellation = cancellation,
                deploymentId = deploymentId,
                registryKey = BiObjectKey(options.consumerDatabase, registryName),
                clusterParameters = clusterParameters,
                registryParameters = clusterParameters + mapOf(
                    "registryDatabase" to options.consumerDatabase,
                    "registryTable" to registryName,
                    "deploymentId" to deploymentId,
                ),
            )
            val nodes = readClusterNodes(context)
            if (!readAndValidateRegistryTables(context, nodes)) {
                return null
            }
            val head = readConsistentHead(context, nodes) ?: return null
            return readConsistentRegistry(context, nodes, head)
        }

        private fun readClusterNodes(context: ClusterRegistryReadContext): Set<ClickHouseCatalogNode> {
            val nodes = catalogClient.query(
                sql = CLUSTER_NODES_QUERY,
                parameters = context.clusterParameters,
                columns = NODE_COLUMNS,
                cancellation = context.cancellation,
            ).map(ClickHouseCatalogRecord::toNode).toSet()
            check(nodes.isNotEmpty()) {
                "ClickHouse BI cluster [${context.topology.name}] returned no replicas"
            }
            return nodes
        }

        private fun readAndValidateRegistryTables(
            context: ClusterRegistryReadContext,
            nodes: Set<ClickHouseCatalogNode>,
        ): Boolean {
            val tablesByNode = catalogClient.query(
                sql = CLUSTER_REGISTRY_TABLE_QUERY,
                parameters = context.registryParameters,
                columns = NODE_COLUMNS + REGISTRY_TABLE_COLUMNS,
                cancellation = context.cancellation,
            ).filter { record -> record.toObjectKey() == context.registryKey }
                .groupBy(ClickHouseCatalogRecord::toNode)
            if (tablesByNode.isEmpty()) {
                return false
            }
            check(tablesByNode.keys == nodes) {
                "ClickHouse BI ownership registry is missing from a cluster replica"
            }
            val columnsByNode = catalogClient.query(
                sql = CLUSTER_REGISTRY_COLUMNS_QUERY,
                parameters = context.registryParameters,
                columns = NODE_COLUMNS + COLUMN_COLUMNS,
                cancellation = context.cancellation,
            ).groupBy(ClickHouseCatalogRecord::toNode)
            nodes.forEach { node ->
                val tables = tablesByNode.getValue(node)
                check(tables.size == 1) {
                    "ClickHouse BI ownership registry is duplicated on a cluster replica"
                }
                ClickHouseOwnershipRegistryShapeValidator.validate(
                    topology = context.topology,
                    deploymentId = context.deploymentId,
                    table = tables.single().toRegistryTableDefinition(),
                    columns = columnsByNode[node].orEmpty().map(ClickHouseCatalogRecord::toCatalogColumn),
                )
            }
            return true
        }

        private fun readConsistentHead(
            context: ClusterRegistryReadContext,
            nodes: Set<ClickHouseCatalogNode>,
        ): ClickHouseRegistryHead? {
            val headsByNode = catalogClient.query(
                sql = CLUSTER_REGISTRY_HEAD_QUERY,
                parameters = context.registryParameters,
                columns = NODE_COLUMNS + REGISTRY_HEAD_COLUMNS,
                cancellation = context.cancellation,
            ).groupBy(ClickHouseCatalogRecord::toNode)
            val heads = nodes.associateWith { node ->
                resolveRegistryHead(headsByNode[node].orEmpty().map(ClickHouseCatalogRecord::toRegistryHead))
            }
            if (heads.values.all { head -> head == null }) {
                return null
            }
            check(heads.values.none { head -> head == null }) {
                "ClickHouse BI ownership registry HEAD is missing from a cluster replica"
            }
            return heads.values.filterNotNull().distinct().also { distinctHeads ->
                check(distinctHeads.size == 1) {
                    "ClickHouse BI ownership registry HEAD differs across replicas"
                }
            }.single()
        }

        private fun readConsistentRegistry(
            context: ClusterRegistryReadContext,
            nodes: Set<ClickHouseCatalogNode>,
            head: ClickHouseRegistryHead,
        ): BiOwnershipRegistry {
            val rowsByNode = catalogClient.query(
                sql = CLUSTER_REGISTRY_ENTRIES_QUERY,
                parameters = context.registryParameters,
                columns = NODE_COLUMNS + REGISTRY_COLUMNS,
                cancellation = context.cancellation,
            ).groupBy(ClickHouseCatalogRecord::toNode)
            val registries = nodes.map { node ->
                restoreRegistry(
                    deploymentId = context.deploymentId,
                    head = head,
                    rows = rowsByNode[node].orEmpty().map(ClickHouseCatalogRecord::toRegistryRow),
                )
            }
            val snapshots = registries.map { registry ->
                RegistryReplicaSnapshot(
                    revision = registry.revision,
                    fingerprint = registry.snapshotFingerprint(),
                    entries = registry.entries,
                )
            }.distinct()
            check(snapshots.size == 1) {
                "ClickHouse BI ownership registry object snapshot differs across replicas"
            }
            return registries.first()
        }

        private fun resolveRegistryHead(heads: List<ClickHouseRegistryHead>): ClickHouseRegistryHead? {
            if (heads.isEmpty()) {
                // CREATE TABLE is intentionally before the write-ahead HEAD.
                return null
            }
            val latestRevision = heads.maxOf(ClickHouseRegistryHead::revision)
            val latestHeads = heads.filter { head -> head.revision == latestRevision }.distinct()
            check(latestHeads.size == 1) {
                "ClickHouse BI ownership registry has conflicting HEAD rows at " +
                    "revision=$latestRevision"
            }
            return latestHeads.single().also { head ->
                check(head.rowFingerprint == head.snapshotFingerprint) {
                    "ClickHouse BI ownership registry HEAD row fingerprint is invalid"
                }
            }
        }

        private fun restoreRegistry(
            deploymentId: String,
            head: ClickHouseRegistryHead,
            rows: List<ClickHouseRegistryRow>,
        ): BiOwnershipRegistry {
            check(rows.all { row -> row.entry.revision <= head.revision }) {
                "ClickHouse BI ownership registry object revision is ahead of its HEAD"
            }
            val entries = rows.groupBy { row -> row.entry.key }.map { (key, keyRows) ->
                val latestObjectRevision = keyRows.maxOf { row -> row.entry.revision }
                val latestRows = keyRows.filter { row -> row.entry.revision == latestObjectRevision }.distinct()
                check(latestRows.size == 1) {
                    "ClickHouse BI ownership registry has conflicting object rows for " +
                        "[${key.database}.${key.name}] at revision=$latestObjectRevision"
                }
                latestRows.single().also { row ->
                    check(row.entry.rowFingerprint() == row.rowFingerprint) {
                        "ClickHouse BI ownership registry object row fingerprint is invalid for " +
                            "[${key.database}.${key.name}]"
                    }
                }.entry
            }.sortedWith(compareBy({ it.key.database }, { it.key.name }))
            val registry = BiOwnershipRegistry.restore(
                deploymentId = deploymentId,
                revision = head.revision,
                entries = entries,
            )
            check(registry.snapshotFingerprint() == head.snapshotFingerprint) {
                "ClickHouse BI ownership registry HEAD fingerprint does not match its object snapshot"
            }
            return registry
        }
    }

    private fun canonicalizeExpectedQueries(
        request: ClickHouseCatalogReadRequest,
        objects: List<ClickHouseCatalogObject>,
    ): Map<BiObjectKey, CanonicalExpectedBiQuery> = with(request) {
        if (!shouldCanonicalizeExpectedQueries(objects)) {
            return emptyMap()
        }
        val requestedObjects = requireNotNull(desiredObjects)
        val observedKeys = objects.mapTo(hashSetOf(), ClickHouseCatalogObject::key)
        val desiredQueries = requestedObjects.asSequence()
            .filter { desired -> desired.key in observedKeys }
            .mapNotNull { desired -> desired.expectedQuery?.let { desired.key to it } }
            .toMap()
        if (desiredQueries.isEmpty()) {
            return emptyMap()
        }
        val definitionPlan = ClickHouseStringArrayParameterPlan.create(
            expression = "definition",
            parameterName = "expectedDefinitions",
            values = desiredQueries.map { (key, query) ->
                JsonSerializer.writeValueAsString(ExpectedQueryWire(key.database, key.name, query.selectSql))
            },
        )
        val canonicalSelects = catalogClient.query(
            sql = EXPECTED_QUERY_CANONICALIZATION_QUERY.replace(
                EXPECTED_DEFINITIONS_EXPRESSION,
                definitionPlan.arrayExpression,
            ),
            parameters = definitionPlan.parameters,
            columns = EXPECTED_QUERY_COLUMNS,
            cancellation = cancellation,
        ).map(ClickHouseCatalogRecord::toCanonicalExpectedQuery).toMap()
        check(canonicalSelects.keys == desiredQueries.keys) {
            "ClickHouse did not canonicalize every expected BI computed-object query"
        }
        return desiredQueries.mapValues { (key, query) ->
            CanonicalExpectedBiQuery(
                selectSql = checkNotNull(canonicalSelects[key]),
                target = query.target,
            )
        }
    }

    private fun readStandalone(
        context: StandaloneReadContext,
        desiredObjectKeys: Set<BiObjectKey>?,
        registryAuthoritative: Boolean,
    ): List<ClickHouseCatalogObject> = with(context) {
        val query = if (desiredObjectKeys == null) {
            ClickHouseCatalogQuery(STANDALONE_CATALOG_QUERY, options.catalogParameters())
        } else if (registryAuthoritative) {
            options.catalogScopeQuery(desiredObjectKeys, STANDALONE_SCOPED_CATALOG_QUERY, false)
        } else {
            val candidates = discoverObjectKeys(
                query = options.catalogScopeQuery(desiredObjectKeys, STANDALONE_CATALOG_DISCOVERY_QUERY, true),
                cancellation = cancellation,
            )
            if (candidates.isEmpty()) {
                return emptyList()
            }
            options.catalogScopeQuery(candidates, STANDALONE_SCOPED_CATALOG_QUERY, false)
        }
        val objects = catalogClient.query(
            sql = query.sql,
            parameters = query.parameters,
            columns = CATALOG_COLUMNS,
            cancellation = cancellation,
        ).map(ClickHouseCatalogRecord::toCatalogObject)
        return loadStandaloneStoreColumns(options, operation, objects, cancellation)
    }

    private fun readCluster(
        context: ClusterReadContext,
        desiredObjectKeys: Set<BiObjectKey>?,
        registryAuthoritative: Boolean,
    ): List<ClickHouseCatalogObject> = with(context) {
        val nodes = catalogClient.query(
            sql = CLUSTER_NODES_QUERY,
            parameters = mapOf("cluster" to cluster.name),
            columns = NODE_COLUMNS,
            cancellation = cancellation,
        ).map(ClickHouseCatalogRecord::toNode).toSet()
        check(nodes.isNotEmpty()) {
            "ClickHouse BI cluster [${cluster.name}] returned no replicas"
        }
        val query = if (desiredObjectKeys == null) {
            ClickHouseCatalogQuery(CLUSTER_CATALOG_QUERY, options.catalogParameters())
        } else if (registryAuthoritative) {
            options.catalogScopeQuery(desiredObjectKeys, CLUSTER_SCOPED_CATALOG_QUERY, false)
        } else {
            val discoveryQuery = options.catalogScopeQuery(
                desiredObjectKeys,
                CLUSTER_CATALOG_DISCOVERY_QUERY,
                true,
            )
            val candidates = discoverObjectKeys(
                query = discoveryQuery.copy(parameters = discoveryQuery.parameters + ("cluster" to cluster.name)),
                cancellation = cancellation,
            )
            if (candidates.isEmpty()) {
                return emptyList()
            }
            options.catalogScopeQuery(candidates, CLUSTER_SCOPED_CATALOG_QUERY, false)
        }
        var objects = catalogClient.query(
            sql = query.sql,
            parameters = query.parameters + ("cluster" to cluster.name),
            columns = NODE_COLUMNS + CATALOG_COLUMNS,
            cancellation = cancellation,
        ).map { record -> NodeObject(record.toNode(), record.toCatalogObject()) }
        validateClusterCatalog(cluster.name, nodes, objects)
        objects = loadClusterStoreColumns(context, objects)
        return objects.map(NodeObject::objectValue)
    }

    private fun discoverObjectKeys(
        query: ClickHouseCatalogQuery,
        cancellation: ClickHouseQueryCancellation,
    ): Set<BiObjectKey> = catalogClient.query(
        sql = query.sql,
        parameters = query.parameters,
        columns = OBJECT_KEY_COLUMNS,
        cancellation = cancellation,
    ).mapTo(linkedSetOf(), ClickHouseCatalogRecord::toObjectKey)

    private fun loadStandaloneStoreColumns(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        objects: List<ClickHouseCatalogObject>,
        cancellation: ClickHouseQueryCancellation,
    ): List<ClickHouseCatalogObject> {
        val stores = storesRequiringShapeValidation(options, operation, objects)
        if (stores.isEmpty()) {
            return objects
        }
        val tablePlan = ClickHouseStringArrayParameterPlan.create(
            expression = "table",
            parameterName = "tables",
            values = stores.map { it.observed.name }.distinct(),
        )
        val columnsByTable = catalogClient.query(
            sql = STANDALONE_COLUMNS_QUERY.replace(TABLES_PREDICATE, tablePlan.predicate),
            parameters = mapOf("database" to options.database) + tablePlan.parameters,
            columns = COLUMN_COLUMNS,
            cancellation = cancellation,
        ).map(ClickHouseCatalogRecord::toCatalogColumn)
            .groupBy(ClickHouseCatalogColumn::key)
        val storeKeys = stores.mapTo(mutableSetOf(), ClickHouseCatalogObject::key)
        return objects.map { catalogObject ->
            if (catalogObject.key in storeKeys) {
                catalogObject.copy(columns = columnsByTable[catalogObject.key].orEmpty().sortedBy { it.position })
            } else {
                catalogObject
            }
        }
    }

    private fun loadClusterStoreColumns(
        context: ClusterReadContext,
        objects: List<NodeObject>,
    ): List<NodeObject> = with(context) {
        val stores = storesRequiringShapeValidation(options, operation, objects.map(NodeObject::objectValue))
        if (stores.isEmpty()) {
            return objects
        }
        val storeKeys = stores.mapTo(mutableSetOf(), ClickHouseCatalogObject::key)
        val tablePlan = ClickHouseStringArrayParameterPlan.create(
            expression = "table",
            parameterName = "tables",
            values = stores.map { it.observed.name }.distinct(),
        )
        val columnsByNodeAndTable = catalogClient.query(
            sql = CLUSTER_COLUMNS_QUERY.replace(TABLES_PREDICATE, tablePlan.predicate),
            parameters = mapOf(
                "cluster" to cluster.name,
                "database" to options.database,
            ) + tablePlan.parameters,
            columns = NODE_COLUMNS + COLUMN_COLUMNS,
            cancellation = cancellation,
        ).map { record -> NodeColumn(record.toNode(), record.toCatalogColumn()) }
            .groupBy { NodeColumnKey(it.node, it.column.key) }
        return objects.map { nodeObject ->
            if (nodeObject.objectValue.key !in storeKeys) {
                return@map nodeObject
            }
            val columns = columnsByNodeAndTable[NodeColumnKey(nodeObject.node, nodeObject.objectValue.key)]
                .orEmpty()
                .map(NodeColumn::column)
                .sortedBy(ClickHouseCatalogColumn::position)
            nodeObject.copy(objectValue = nodeObject.objectValue.copy(columns = columns))
        }
    }

    private fun storesRequiringShapeValidation(
        options: BiScriptOptions,
        operation: BiScriptOperation,
        objects: List<ClickHouseCatalogObject>,
    ): List<ClickHouseCatalogObject> {
        if (operation != BiScriptOperation.Deploy) {
            return emptyList()
        }
        val descriptor = BiDeploymentDescriptor.from(options)
        if (!requestedDeploymentIsStable(descriptor, objects)) {
            return emptyList()
        }
        return objects.filter { catalogObject ->
            catalogObject.observed.metadata?.let { metadata ->
                metadata.kind == BiObjectKind.STORE && metadata.deploymentId == descriptor.deploymentId
            } == true
        }
    }

    private fun validateClusterCatalog(
        cluster: String,
        nodes: Set<ClickHouseCatalogNode>,
        objects: List<NodeObject>,
    ) {
        val observedNodes = objects.mapTo(mutableSetOf(), NodeObject::node)
        check(observedNodes.all(nodes::contains)) {
            "ClickHouse BI cluster [$cluster] catalog contains an unknown replica"
        }
        objects.groupBy { it.objectValue.key }.forEach { (key, replicas) ->
            if (replicas.none { it.objectValue.observed.metadata != null }) {
                return@forEach
            }
            check(replicas.size == nodes.size && replicas.mapTo(mutableSetOf(), NodeObject::node) == nodes) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] is missing from a replica"
            }
            val definitions = replicas.map { it.objectValue.toCatalogDefinition() }.distinct()
            check(definitions.size == 1) {
                "ClickHouse BI catalog object [${key.database}.${key.name}] differs across replicas"
            }
        }
    }

    private data class ClusterReadContext(
        val options: BiScriptOptions,
        val cluster: ClickHouseTopology.Cluster,
        val operation: BiScriptOperation,
        val cancellation: ClickHouseQueryCancellation,
    )

    private data class StandaloneReadContext(
        val options: BiScriptOptions,
        val operation: BiScriptOperation,
        val cancellation: ClickHouseQueryCancellation,
    )

    private data class ClusterRegistryReadContext(
        val topology: ClickHouseTopology.Cluster,
        val cancellation: ClickHouseQueryCancellation,
        val deploymentId: String,
        val registryKey: BiObjectKey,
        val clusterParameters: Map<String, Any>,
        val registryParameters: Map<String, Any>,
    )

    private data class NodeObject(val node: ClickHouseCatalogNode, val objectValue: ClickHouseCatalogObject)
    private data class NodeColumn(val node: ClickHouseCatalogNode, val column: ClickHouseCatalogColumn)
    private data class NodeColumnKey(val node: ClickHouseCatalogNode, val key: BiObjectKey)
    private data class RegistryReplicaSnapshot(
        val revision: Long,
        val fingerprint: String,
        val entries: List<BiOwnershipRegistryEntry>,
    )

    private companion object {
        val NODE_COLUMNS = listOf("host_name", "tcp_port")
        val CATALOG_COLUMNS = listOf(
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
        val EXPECTED_QUERY_COLUMNS = listOf("database", "name", "canonical_select")
        val OBJECT_KEY_COLUMNS = listOf("database", "name")
        val COLUMN_COLUMNS = listOf("database", "table", "name", "type", "position")
        val REGISTRY_COLUMNS = listOf(
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
        val REGISTRY_HEAD_COLUMNS = listOf(
            "revision",
            "snapshot_fingerprint",
            "row_fingerprint",
        )

        val REGISTRY_TABLE_COLUMNS = listOf(
            "database",
            "name",
            "engine",
            "engine_full",
            "comment",
            "sorting_key",
        )

        val REGISTRY_TABLE_QUERY: String = """
            SELECT database, name, engine, engine_full, comment, sorting_key
            FROM system.tables
            WHERE database = {registryDatabase:String}
              AND name = {registryTable:String}
        """.trimIndent()

        val CLUSTER_REGISTRY_TABLE_QUERY: String = """
            SELECT hostName() AS host_name, tcpPort() AS tcp_port,
                   database, name, engine, engine_full, comment, sorting_key
            FROM clusterAllReplicas({cluster:String}, system.tables)
            WHERE database = {registryDatabase:String}
              AND name = {registryTable:String}
            SETTINGS skip_unavailable_shards = 0
        """.trimIndent()

        val REGISTRY_COLUMNS_QUERY: String = """
            SELECT database, table, name, type, position
            FROM system.columns
            WHERE database = {registryDatabase:String}
              AND table = {registryTable:String}
            ORDER BY position
        """.trimIndent()

        val CLUSTER_REGISTRY_COLUMNS_QUERY: String = """
            SELECT hostName() AS host_name, tcpPort() AS tcp_port,
                   database, table, name, type, position
            FROM clusterAllReplicas({cluster:String}, system.columns)
            WHERE database = {registryDatabase:String}
              AND table = {registryTable:String}
            ORDER BY host_name, tcp_port, position
            SETTINGS skip_unavailable_shards = 0
        """.trimIndent()

        val REGISTRY_HEAD_QUERY: String = """
            SELECT revision,
                   definition_fingerprint AS snapshot_fingerprint, row_fingerprint
            FROM {registryDatabase:Identifier}.{registryTable:Identifier}
            WHERE deployment_id = {deploymentId:String}
              AND row_kind = 'HEAD'
            ORDER BY revision DESC
        """.trimIndent()

        val CLUSTER_REGISTRY_HEAD_QUERY: String = """
            SELECT hostName() AS host_name, tcpPort() AS tcp_port,
                   revision,
                   definition_fingerprint AS snapshot_fingerprint, row_fingerprint
            FROM clusterAllReplicas(
                {cluster:String},
                {registryDatabase:Identifier}.{registryTable:Identifier}
            )
            WHERE deployment_id = {deploymentId:String}
              AND row_kind = 'HEAD'
            ORDER BY host_name, tcp_port, revision DESC
            SETTINGS skip_unavailable_shards = 0
        """.trimIndent()

        val REGISTRY_ENTRIES_QUERY: String = """
            SELECT object_database, object_name, kind,
                   aggregate, consumer_identity, definition_fingerprint,
                   revision, status, row_fingerprint
            FROM {registryDatabase:Identifier}.{registryTable:Identifier}
            WHERE deployment_id = {deploymentId:String}
              AND row_kind = 'OBJECT'
            ORDER BY object_database, object_name, revision DESC
        """.trimIndent()

        val CLUSTER_REGISTRY_ENTRIES_QUERY: String = """
            SELECT hostName() AS host_name, tcpPort() AS tcp_port,
                   object_database, object_name, kind,
                   aggregate, consumer_identity, definition_fingerprint,
                   revision, status, row_fingerprint
            FROM clusterAllReplicas(
                {cluster:String},
                {registryDatabase:Identifier}.{registryTable:Identifier}
            )
            WHERE deployment_id = {deploymentId:String}
              AND row_kind = 'OBJECT'
            ORDER BY host_name, tcp_port, object_database, object_name, revision DESC
            SETTINGS skip_unavailable_shards = 0
        """.trimIndent()

        val STANDALONE_CATALOG_QUERY: String = """
            SELECT database, name, engine, engine_full, create_table_query,
                   formatQuerySingleLineOrNull(as_select) AS as_select,
                   comment, partition_key, sorting_key
            FROM system.tables
            WHERE database IN ({database:String}, {consumerDatabase:String})
            SETTINGS show_table_uuid_in_table_create_query_if_not_nil = 0
        """.trimIndent()

        val STANDALONE_CATALOG_DISCOVERY_QUERY: String = """
            SELECT database, name
            FROM system.tables
            WHERE database IN ({database:String}, {consumerDatabase:String})
              AND (startsWith(comment, {ownershipPrefix:String})
                   OR (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
                   OR (database = {consumerDatabase:String}
                       AND $CONSUMER_DATABASE_TABLES_PREDICATE))
        """.trimIndent()

        val STANDALONE_SCOPED_CATALOG_QUERY: String = """
            SELECT database, name, engine, engine_full, create_table_query,
                   formatQuerySingleLineOrNull(as_select) AS as_select,
                   comment, partition_key, sorting_key
            FROM system.tables
            WHERE (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
               OR (database = {consumerDatabase:String} AND $CONSUMER_DATABASE_TABLES_PREDICATE)
            SETTINGS show_table_uuid_in_table_create_query_if_not_nil = 0
        """.trimIndent()

        val CLUSTER_NODES_QUERY: String = """
            SELECT hostName() AS host_name, tcpPort() AS tcp_port
            FROM clusterAllReplicas({cluster:String}, system.one)
            SETTINGS skip_unavailable_shards = 0,
                     show_table_uuid_in_table_create_query_if_not_nil = 0
        """.trimIndent()

        val CLUSTER_CATALOG_QUERY: String = """
            SELECT hostName() AS host_name,
                   tcpPort() AS tcp_port,
                   database,
                   name,
                   engine,
                   engine_full,
                   create_table_query,
                   formatQuerySingleLineOrNull(as_select) AS as_select,
                   comment,
                   partition_key,
                   sorting_key
            FROM clusterAllReplicas({cluster:String}, system.tables)
            WHERE database IN ({database:String}, {consumerDatabase:String})
            SETTINGS skip_unavailable_shards = 0,
                     show_table_uuid_in_table_create_query_if_not_nil = 0
        """.trimIndent()

        val CLUSTER_CATALOG_DISCOVERY_QUERY: String = """
            SELECT DISTINCT database, name
            FROM clusterAllReplicas({cluster:String}, system.tables)
            WHERE database IN ({database:String}, {consumerDatabase:String})
              AND (startsWith(comment, {ownershipPrefix:String})
                   OR (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
                   OR (database = {consumerDatabase:String}
                       AND $CONSUMER_DATABASE_TABLES_PREDICATE))
            SETTINGS skip_unavailable_shards = 0,
                     max_parallel_replicas = 1
        """.trimIndent()

        val CLUSTER_SCOPED_CATALOG_QUERY: String = """
            SELECT hostName() AS host_name,
                   tcpPort() AS tcp_port,
                   database,
                   name,
                   engine,
                   engine_full,
                   create_table_query,
                   formatQuerySingleLineOrNull(as_select) AS as_select,
                   comment,
                   partition_key,
                   sorting_key
            FROM clusterAllReplicas({cluster:String}, system.tables)
            WHERE (database = {database:String} AND $DATABASE_TABLES_PREDICATE)
               OR (database = {consumerDatabase:String} AND $CONSUMER_DATABASE_TABLES_PREDICATE)
            SETTINGS skip_unavailable_shards = 0,
                     show_table_uuid_in_table_create_query_if_not_nil = 0
        """.trimIndent()

        val STANDALONE_COLUMNS_QUERY: String = """
            SELECT database, table, name, type, position
            FROM system.columns
            WHERE database = {database:String}
              AND $TABLES_PREDICATE
        """.trimIndent()

        val CLUSTER_COLUMNS_QUERY: String = """
            SELECT hostName() AS host_name,
                   tcpPort() AS tcp_port,
                   database,
                   table,
                   name,
                   type,
                   position
            FROM clusterAllReplicas({cluster:String}, system.columns)
            WHERE database = {database:String}
              AND $TABLES_PREDICATE
            SETTINGS skip_unavailable_shards = 0,
                     max_parallel_replicas = 1
        """.trimIndent()

        val EXPECTED_QUERY_CANONICALIZATION_QUERY: String = """
            SELECT JSONExtractString(definition, 'database') AS database,
                   JSONExtractString(definition, 'name') AS name,
                   formatQuerySingleLineOrNull(JSONExtractString(definition, 'selectSql')) AS canonical_select
            FROM (
                SELECT arrayJoin($EXPECTED_DEFINITIONS_EXPRESSION) AS definition
            )
        """.trimIndent()
    }
}

private fun validateRegistryAnchor(
    registry: BiOwnershipRegistry?,
    objects: List<ClickHouseCatalogObject>,
    operation: BiScriptOperation,
) {
    val anchor = objects.asSequence()
        .map(ClickHouseCatalogObject::observed)
        .mapNotNull(ObservedBiObject::metadata)
        .singleOrNull { metadata -> metadata.kind == BiObjectKind.ANCHOR }
        ?: return
    val anchorRevision = anchor.registryRevision ?: 0
    if (registry == null) {
        check(anchorRevision == 0L || operation is BiScriptOperation.Reset) {
            "ClickHouse BI deployment anchor requires ownership registry revision $anchorRevision, " +
                "but the registry is missing; explicit repair is required"
        }
        return
    }
    check(anchorRevision <= registry.revision) {
        "ClickHouse BI deployment anchor registry revision $anchorRevision is ahead of " +
            "ownership registry revision ${registry.revision}"
    }
}

private fun BiOwnershipRegistry.validateCatalogPresence(
    objects: List<ClickHouseCatalogObject>,
    operation: BiScriptOperation,
) {
    if (operation is BiScriptOperation.Reset) {
        return
    }
    val observedKeys = objects.mapTo(hashSetOf(), ClickHouseCatalogObject::key)
    val requiredKeys = entries.asSequence()
        .filter { entry ->
            entry.status in setOf(BiRegistryEntryStatus.ACTIVE, BiRegistryEntryStatus.RETIRED)
        }
        .map(BiOwnershipRegistryEntry::key)
        .filterNot { key -> key in observedKeys }
        .toList()
    check(requiredKeys.isEmpty()) {
        "ClickHouse BI ownership registry references missing ACTIVE/RETIRED objects: " +
            requiredKeys.joinToString { key -> "${key.database}.${key.name}" }
    }
    val presentTombstones = entries.asSequence()
        .filter { entry -> entry.status == BiRegistryEntryStatus.TOMBSTONE }
        .map(BiOwnershipRegistryEntry::key)
        .filter { key -> key in observedKeys }
        .toList()
    check(presentTombstones.isEmpty()) {
        "ClickHouse BI ownership registry TOMBSTONE objects still exist: " +
            presentTombstones.joinToString { key -> "${key.database}.${key.name}" }
    }
}

internal data class ClickHouseCatalogReadRequest(
    val options: BiScriptOptions,
    val operation: BiScriptOperation,
    val desiredObjectKeys: Set<BiObjectKey>?,
    val desiredObjects: List<DesiredBiObject>?,
    val cancellation: ClickHouseQueryCancellation,
)

internal data class ClickHouseCatalogSnapshot(
    val objects: List<ClickHouseCatalogObject>,
    val expectedQueries: Map<BiObjectKey, CanonicalExpectedBiQuery>,
    val ownershipRegistry: BiOwnershipRegistry?,
)

private data class ExpectedQueryWire(
    val database: String,
    val name: String,
    val selectSql: String,
)

private data class ClickHouseCatalogQuery(val sql: String, val parameters: Map<String, Any>)

private fun BiScriptOptions.catalogParameters(): Map<String, Any> = mapOf(
    "database" to database,
    "consumerDatabase" to consumerDatabase,
)

private fun BiScriptOptions.catalogScopeQuery(
    objectKeys: Set<BiObjectKey>,
    sql: String,
    includeOwnershipPrefix: Boolean,
): ClickHouseCatalogQuery {
    val namesByDatabase = objectKeys.groupBy(BiObjectKey::database, BiObjectKey::name)
    val databasePlan = ClickHouseStringArrayParameterPlan.create(
        expression = "name",
        parameterName = "databaseTables",
        values = namesByDatabase[database].orEmpty().sorted(),
    )
    val consumerDatabasePlan = ClickHouseStringArrayParameterPlan.create(
        expression = "name",
        parameterName = "consumerDatabaseTables",
        values = namesByDatabase[consumerDatabase].orEmpty().sorted(),
    )
    val parameters = mutableMapOf<String, Any>(
        "database" to database,
        "consumerDatabase" to consumerDatabase,
    )
    if (includeOwnershipPrefix) {
        parameters["ownershipPrefix"] = BI_OBJECT_METADATA_PREFIX
    }
    parameters.putAll(databasePlan.parameters)
    parameters.putAll(consumerDatabasePlan.parameters)
    return ClickHouseCatalogQuery(
        sql = sql
            .replace(DATABASE_TABLES_PREDICATE, databasePlan.predicate)
            .replace(CONSUMER_DATABASE_TABLES_PREDICATE, consumerDatabasePlan.predicate),
        parameters = parameters,
    )
}

private fun ClickHouseCatalogReadRequest.shouldCanonicalizeExpectedQueries(
    objects: List<ClickHouseCatalogObject>,
): Boolean {
    if (desiredObjects == null) {
        return false
    }
    if (operation != BiScriptOperation.Deploy) {
        return false
    }
    return requestedDeploymentIsStable(BiDeploymentDescriptor.from(options), objects)
}

internal fun requestedDeploymentIsStable(
    descriptor: BiDeploymentDescriptor,
    objects: List<ClickHouseCatalogObject>,
): Boolean = objects.asSequence()
    .mapNotNull { it.observed.metadata }
    .filter { metadata -> metadata.deploymentId == descriptor.deploymentId }
    .all { metadata ->
        metadata.phase == BiDeploymentPhase.STABLE &&
            metadata.configurationFingerprint == descriptor.configurationFingerprint
    }

internal fun ClickHouseCatalogObject.toCatalogDefinition(): ClickHouseCatalogDefinition = ClickHouseCatalogDefinition(
    engine = observed.engine,
    engineFull = observed.engineFull,
    createTableQuery = observed.createTableQuery,
    asSelect = asSelect,
    metadata = observed.metadata,
)

internal data class ClickHouseCatalogDefinition(
    val engine: String,
    val engineFull: String,
    val createTableQuery: String,
    val asSelect: String,
    val metadata: BiObjectMetadata?,
)
