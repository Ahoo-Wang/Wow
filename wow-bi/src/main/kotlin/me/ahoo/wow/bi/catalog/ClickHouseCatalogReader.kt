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

package me.ahoo.wow.bi.catalog

import me.ahoo.wow.bi.BI_OBJECT_METADATA_PREFIX
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiDeploymentPhase
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiObjectMetadata
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.CanonicalExpectedBiQuery
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.DesiredBiObject
import me.ahoo.wow.serialization.JsonSerializer

internal class ClickHouseCatalogReader(private val catalogClient: ClickHouseCatalogClient) {
    fun read(request: ClickHouseCatalogReadRequest): ClickHouseCatalogSnapshot = with(request) {
        val objects = when (val topology = options.topology) {
            ClickHouseTopology.Standalone -> readStandalone(
                StandaloneReadContext(options, operation, cancellation),
                desiredObjectKeys,
            )
            is ClickHouseTopology.Cluster -> readCluster(
                ClusterReadContext(options, topology, operation, cancellation),
                desiredObjectKeys,
            )
        }
        return ClickHouseCatalogSnapshot(
            objects = objects,
            expectedQueries = canonicalizeExpectedQueries(request, objects),
        )
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
    ): List<ClickHouseCatalogObject> = with(context) {
        val query = if (desiredObjectKeys == null) {
            ClickHouseCatalogQuery(STANDALONE_CATALOG_QUERY, options.catalogParameters())
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

    private data class NodeObject(val node: ClickHouseCatalogNode, val objectValue: ClickHouseCatalogObject)
    private data class NodeColumn(val node: ClickHouseCatalogNode, val column: ClickHouseCatalogColumn)
    private data class NodeColumnKey(val node: ClickHouseCatalogNode, val key: BiObjectKey)
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

/** The deployment has no anchor yet, or its anchor is STABLE with the requested configuration. */
internal fun requestedDeploymentIsStable(
    descriptor: BiDeploymentDescriptor,
    objects: List<ClickHouseCatalogObject>,
): Boolean {
    val owned = objects.mapNotNull { it.observed.metadata }
        .filter { metadata -> metadata.deploymentId == descriptor.deploymentId }
    if (owned.any { metadata -> !metadata.isCurrentLayout }) {
        return false
    }
    val anchor = owned.firstOrNull { metadata -> metadata.kind == BiObjectKind.ANCHOR }?.anchor ?: return true
    return anchor.phase == BiDeploymentPhase.STABLE &&
        anchor.configurationFingerprint == descriptor.configurationFingerprint
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
