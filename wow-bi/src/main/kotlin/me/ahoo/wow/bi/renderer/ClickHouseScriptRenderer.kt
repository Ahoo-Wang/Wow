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

package me.ahoo.wow.bi.renderer

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiDeploymentPhase
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiObjectMetadata
import me.ahoo.wow.bi.BiObjectMetadataCodec
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.KafkaOffsetStorage
import me.ahoo.wow.bi.ObservedBiObject
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.expansion.plan.CollectionCursorPlan
import me.ahoo.wow.bi.expansion.plan.ColumnExtraction
import me.ahoo.wow.bi.expansion.plan.ColumnPlacement
import me.ahoo.wow.bi.expansion.plan.ColumnPlan
import me.ahoo.wow.bi.expansion.plan.ColumnReference
import me.ahoo.wow.bi.expansion.plan.ExpansionViewPlan
import me.ahoo.wow.bi.expansion.plan.JsonPointerSegment
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.quoteIdentifier
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.stringLiteral
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.modeling.toStringWithAlias
import java.util.Collections

internal enum class CatalogMutationMode {
    RECONCILE,
    CREATE_ONLY,
}

// Keeping the complete ClickHouse graph here preserves shared naming, quoting, and ownership semantics.
@Suppress("LargeClass")
internal class ClickHouseScriptRenderer(
    private val options: BiScriptOptions = BiScriptOptions(consumerGroupNamespace = "test"),
    private val consumerIdentity: BiConsumerIdentity =
        BiConsumerIdentity.deterministic(BiDeploymentDescriptor.from(options)),
    private val deployment: BiDeploymentDescriptor = BiDeploymentDescriptor.from(options),
    private val catalogMutationMode: CatalogMutationMode = CatalogMutationMode.RECONCILE,
    private val retainedQueueKeys: Set<BiObjectKey> = emptySet(),
) {
    private val naming = BiTableNaming(options)
    private val topology = options.topology.toDdl()
    private val metadataColumns = buildMetadataColumns(options.timezone)

    fun renderGlobal(): String = renderGlobalStatements().joinToString(STATEMENT_SEPARATOR)

    fun renderGlobalStatements(): List<String> = immutableStatements(
        "CREATE DATABASE IF NOT EXISTS ${identifier(options.database)}${scopeClause()};",
        "CREATE DATABASE IF NOT EXISTS ${identifier(options.consumerDatabase)}${scopeClause()};",
    )

    fun renderClear(namedAggregate: NamedAggregate, expansionTables: List<String>): String =
        renderClearStatements(namedAggregate, expansionTables).joinToString(STATEMENT_SEPARATOR)

    fun renderClearStatements(namedAggregate: NamedAggregate, expansionTables: List<String>): List<String> {
        val commandTable = naming.toTableName(namedAggregate, COMMAND_SUFFIX)
        val stateTable = naming.toTableName(namedAggregate, STATE_SUFFIX)
        val stateLastTable = naming.toTableName(namedAggregate, STATE_LAST_SUFFIX)
        return immutableStatements(
            buildList {
                add(dropView(options.consumerDatabase, "${commandTable}_consumer"))
                add(drop(options.consumerDatabase, "${commandTable}_queue"))
                add(dropView(options.consumerDatabase, "${stateTable}_consumer"))
                add(drop(options.consumerDatabase, "${stateTable}_queue"))
                add(dropView(options.consumerDatabase, "${stateLastTable}_consumer"))
                expansionTables.asReversed().forEach { add(dropView(options.database, it)) }
                add(dropView(options.database, "${stateTable}_event"))
                add(dropView(options.database, commandTable))
                add(dropView(options.database, stateTable))
                add(dropView(options.database, stateLastTable))
                listOf(commandTable, stateTable, stateLastTable).forEach { table ->
                    topology.dropTableNames(storageTable(table)).forEach { add(drop(options.database, it)) }
                }
            }
        )
    }

    fun renderDropExpansionStatements(expansionTables: List<String>): List<String> =
        immutableStatements(expansionTables.asReversed().map { dropView(options.database, it) })

    fun renderDropObservedStatements(objects: List<ObservedBiObject>): List<String> = immutableStatements(
        objects.sortedWith(
            compareBy<ObservedBiObject> { it.metadata?.kind == BiObjectKind.STORE }
                .thenBy { it.metadata?.kind == BiObjectKind.STORE && it.name.endsWith("_local") }
                .thenByDescending { it.name.length }
                .thenBy { it.database }
                .thenBy { it.name }
        ).map { observed ->
            when (observed.metadata?.kind) {
                BiObjectKind.ANCHOR, BiObjectKind.VIEW, BiObjectKind.CONSUMER ->
                    dropView(observed.database, observed.name)
                BiObjectKind.STORE, BiObjectKind.QUEUE -> drop(observed.database, observed.name)
                null -> error("Cannot drop an unowned BI catalog object: ${observed.database}.${observed.name}")
            }
        }
    )

    fun renderAnchorStatement(phase: BiDeploymentPhase = BiDeploymentPhase.STABLE): String {
        val comment = metadataComment(BiObjectKind.ANCHOR, null, phase)
        return "$viewCreateClause ${qualified(options.consumerDatabase, DEPLOYMENT_ANCHOR)}" +
            "${scopeClause()} AS (SELECT 1 AS ${identifier("alive")} WHERE 0) COMMENT $comment;"
    }

    @Suppress("LongMethod")
    fun renderCommand(namedAggregate: NamedAggregate): String =
        renderCommandStatements(namedAggregate).joinToString(STATEMENT_SEPARATOR)

    fun renderCommandStatements(namedAggregate: NamedAggregate): List<String> =
        renderCommandGraphStatements(namedAggregate)

    fun renderCommandStorageStatements(namedAggregate: NamedAggregate): List<String> =
        renderCommandGraphStatements(namedAggregate).take(commandStorageStatementCount())

    fun renderCommandPublicStatements(namedAggregate: NamedAggregate): List<String> =
        renderCommandGraphStatements(namedAggregate).takeLast(COMMAND_PUBLIC_STATEMENT_COUNT)

    fun renderCommandIngressStatements(namedAggregate: NamedAggregate): List<String> =
        renderCommandGraphStatements(namedAggregate)
            .drop(commandStorageStatementCount())
            .dropLast(COMMAND_PUBLIC_STATEMENT_COUNT)

    @Suppress("LongMethod")
    private fun renderCommandGraphStatements(namedAggregate: NamedAggregate): List<String> {
        val aggregate = namedAggregate.toStringWithAlias()
        val storeComment = metadataComment(BiObjectKind.STORE, aggregate)
        val viewComment = metadataComment(BiObjectKind.VIEW, aggregate)
        val queueComment = metadataComment(BiObjectKind.QUEUE, aggregate)
        val consumerComment = metadataComment(BiObjectKind.CONSUMER, aggregate)
        val table = naming.toTableName(namedAggregate, COMMAND_SUFFIX)
        val storeTable = storageTable(table)
        val physicalTable = topology.physicalTableName(storeTable)
        val queueTable = "${table}_queue"
        val consumerTable = "${table}_consumer"
        val topic = naming.toTopicName(namedAggregate, COMMAND_SUFFIX)
        val aggregateVersionJson = jsonValue("data", "aggregateVersion", "Nullable(UInt32)")
        return immutableStatements(
            buildList {
                add(
                    """
                $tableCreateClause ${qualified(options.database, physicalTable)}${scopeClause()}
                (
                    ${identifier("id")} String,
                    ${identifier("context_name")} String,
                    ${identifier("aggregate_name")} String,
                    ${identifier("name")} String,
                    ${identifier("header")} Map(String, String),
                    ${identifier("aggregate_id")} String,
                    ${identifier("tenant_id")} String,
                    ${identifier("owner_id")} String,
                    ${identifier("space_id")} String,
                    ${identifier("request_id")} String,
                    ${identifier("aggregate_version")} Nullable(UInt32),
                    ${identifier("is_create")} Bool,
                    ${identifier("is_void")} Bool,
                    ${identifier("allow_create")} Bool,
                    ${identifier("body_type")} String,
                    ${identifier("body")} String,
                    ${identifier("create_time")} DateTime64(3, ${literal(options.timezone)})
                ) ${topology.engineSql(ReplacingMergeTreeSpec(null))}
                  PARTITION BY toYYYYMM(${identifier("create_time")})
                  ORDER BY ${identifier("id")}
                  COMMENT $storeComment;
                    """.trimIndent()
                )
                topology.distributedFacade(
                    database = options.database,
                    logicalTableName = storeTable,
                    physicalTableName = physicalTable,
                    shardingKey = "sipHash64(${identifier("aggregate_id")})",
                    createIfNotExists = catalogMutationMode == CatalogMutationMode.RECONCILE,
                )?.withTableComment(storeComment)?.let(::add)
                if (catalogMutationMode == CatalogMutationMode.RECONCILE) {
                    add(dropView(options.consumerDatabase, consumerTable))
                }
                if (!isQueueRetained(queueTable)) {
                    add(
                        """
                CREATE TABLE ${qualified(options.consumerDatabase, queueTable)}${scopeClause()}
                (${identifier("data")} String)
                ENGINE = Kafka(${literal(options.kafkaBootstrapServers)}, ${literal(topic)},
                               ${literal(consumerGroup(consumerTable))}, ${literal("JSONAsString")})
                ${kafkaSettings(queueTable, queueComment)};
                        """.trimIndent()
                    )
                }
                add(
                    """
                $materializedViewCreateClause ${qualified(
                        options.consumerDatabase,
                        consumerTable
                    )}${scopeClause()}
                TO ${qualified(options.database, storeTable)}
                AS (
                SELECT ${jsonString("data", "id")} AS ${identifier("id")},
                       ${jsonString("data", "contextName")} AS ${identifier("context_name")},
                       ${jsonString("data", "aggregateName")} AS ${identifier("aggregate_name")},
                       ${jsonString("data", "name")} AS ${identifier("name")},
                       ${jsonValue("data", "header", "Map(String, String)")} AS ${identifier("header")},
                       ${jsonString("data", "aggregateId")} AS ${identifier("aggregate_id")},
                       ${jsonString("data", "tenantId")} AS ${identifier("tenant_id")},
                       ${jsonString("data", "ownerId")} AS ${identifier("owner_id")},
                       ${jsonString("data", "spaceId")} AS ${identifier("space_id")},
                       ${jsonString("data", "requestId")} AS ${identifier("request_id")},
                       $aggregateVersionJson AS ${identifier("aggregate_version")},
                       ${jsonBool("data", "isCreate")} AS ${identifier("is_create")},
                       ${jsonBool("data", "isVoid")} AS ${identifier("is_void")},
                       ${jsonBool("data", "allowCreate")} AS ${identifier("allow_create")},
                       ${jsonString("data", "bodyType")} AS ${identifier("body_type")},
                       ${jsonTopLevelLexicalRaw("data", "body")} AS ${identifier("body")},
                       ${epochMillis("data", "createTime")} AS ${identifier("create_time")}
                FROM ${qualified(options.consumerDatabase, queueTable)}
                ) COMMENT $consumerComment;
                    """.trimIndent()
                )
                add(
                    """
                $viewCreateClause ${qualified(options.database, table)}${scopeClause()}
                AS (SELECT * FROM ${qualified(options.database, storeTable)} FINAL)
                COMMENT $viewComment;
                    """.trimIndent()
                )
            }
        )
    }

    @Suppress("LongMethod")
    fun renderStateEvent(namedAggregate: NamedAggregate): String =
        renderStateEventStatements(namedAggregate).joinToString(STATEMENT_SEPARATOR)

    fun renderStateEventStatements(namedAggregate: NamedAggregate): List<String> = immutableStatements(
        renderStateStorageStatements(namedAggregate) +
            renderStatePublicStatements(namedAggregate) +
            renderStateIngressStatements(namedAggregate)
    )

    fun renderStateStorageStatements(namedAggregate: NamedAggregate): List<String> =
        renderStateEventGraphStatements(namedAggregate).take(stateStorageStatementCount())

    fun renderStatePublicStatements(namedAggregate: NamedAggregate): List<String> =
        renderStateEventGraphStatements(namedAggregate).takeLast(STATE_PUBLIC_STATEMENT_COUNT)

    fun renderStateIngressStatements(namedAggregate: NamedAggregate): List<String> =
        renderStateEventGraphStatements(namedAggregate)
            .drop(stateStorageStatementCount())
            .dropLast(STATE_PUBLIC_STATEMENT_COUNT)

    fun renderPauseIngressStatements(namedAggregate: NamedAggregate): List<String> {
        val commandTable = naming.toTableName(namedAggregate, COMMAND_SUFFIX)
        val stateTable = naming.toTableName(namedAggregate, STATE_SUFFIX)
        return immutableStatements(
            dropView(options.consumerDatabase, "${commandTable}_consumer"),
            dropView(options.consumerDatabase, "${stateTable}_consumer"),
        )
    }

    private fun stateStorageStatementCount(): Int =
        if (options.topology is ClickHouseTopology.Cluster) 2 else 1

    private fun commandStorageStatementCount(): Int =
        if (options.topology is ClickHouseTopology.Cluster) 2 else 1

    @Suppress("LongMethod")
    private fun renderStateEventGraphStatements(namedAggregate: NamedAggregate): List<String> {
        val aggregate = namedAggregate.toStringWithAlias()
        val storeComment = metadataComment(BiObjectKind.STORE, aggregate)
        val viewComment = metadataComment(BiObjectKind.VIEW, aggregate)
        val queueComment = metadataComment(BiObjectKind.QUEUE, aggregate)
        val consumerComment = metadataComment(BiObjectKind.CONSUMER, aggregate)
        val table = naming.toTableName(namedAggregate, STATE_SUFFIX)
        val storeTable = storageTable(table)
        val physicalTable = topology.physicalTableName(storeTable)
        val eventTable = "${table}_event"
        val queueTable = "${table}_queue"
        val consumerTable = "${table}_consumer"
        val topic = naming.toTopicName(namedAggregate, STATE_SUFFIX)
        return immutableStatements(
            buildList {
                add(
                    """
            $tableCreateClause ${qualified(options.database, physicalTable)}${scopeClause()}
            (
                ${identifier("id")} String,
                ${identifier("context_name")} String,
                ${identifier("aggregate_name")} String,
                ${identifier("header")} Map(String, String),
                ${identifier("aggregate_id")} String,
                ${identifier("tenant_id")} String,
                ${identifier("owner_id")} String,
                ${identifier("space_id")} String,
                ${identifier("command_id")} String,
                ${identifier("request_id")} String,
                ${identifier("version")} UInt32,
                ${identifier("state")} String,
                ${identifier("body")} Array(String),
                ${identifier("first_operator")} String,
                ${identifier("first_event_time")} DateTime64(3, ${literal(options.timezone)}),
                ${identifier("create_time")} DateTime64(3, ${literal(options.timezone)}),
                ${identifier("tags")} Map(String, Array(String)),
                ${identifier("deleted")} Bool
            ) ${topology.engineSql(ReplacingMergeTreeSpec("version"))}
                  PARTITION BY toYYYYMM(${identifier("create_time")})
                  ORDER BY (${identifier("aggregate_id")}, ${identifier("version")})
                  COMMENT $storeComment;
                    """.trimIndent()
                )
                topology.distributedFacade(
                    database = options.database,
                    logicalTableName = storeTable,
                    physicalTableName = physicalTable,
                    shardingKey = "sipHash64(${identifier("aggregate_id")})",
                    createIfNotExists = catalogMutationMode == CatalogMutationMode.RECONCILE,
                )?.withTableComment(storeComment)?.let(::add)
                if (catalogMutationMode == CatalogMutationMode.RECONCILE) {
                    add(dropView(options.consumerDatabase, consumerTable))
                }
                if (!isQueueRetained(queueTable)) {
                    add(
                        """
            CREATE TABLE ${qualified(options.consumerDatabase, queueTable)}${scopeClause()}
            (
                ${identifier("data")} String
            ) ENGINE = Kafka(${literal(options.kafkaBootstrapServers)}, ${literal(topic)},
                             ${literal(consumerGroup(consumerTable))}, ${literal("JSONAsString")})
            ${kafkaSettings(queueTable, queueComment)};
                        """.trimIndent()
                    )
                }
                add(
                    """
            $materializedViewCreateClause ${qualified(options.consumerDatabase, consumerTable)}${scopeClause()}
            TO ${qualified(options.database, storeTable)}
            AS (
            SELECT ${jsonString("data", "id")} AS ${identifier("id")},
                   ${jsonString("data", "contextName")} AS ${identifier("context_name")},
                   ${jsonString("data", "aggregateName")} AS ${identifier("aggregate_name")},
                   ${jsonValue("data", "header", "Map(String, String)")} AS ${identifier("header")},
                   ${jsonString("data", "aggregateId")} AS ${identifier("aggregate_id")},
                   ${jsonString("data", "tenantId")} AS ${identifier("tenant_id")},
                   ${jsonString("data", "ownerId")} AS ${identifier("owner_id")},
                   ${jsonString("data", "spaceId")} AS ${identifier("space_id")},
                   ${jsonString("data", "commandId")} AS ${identifier("command_id")},
                   ${jsonString("data", "requestId")} AS ${identifier("request_id")},
                   ${jsonUInt("data", "version")} AS ${identifier("version")},
                   ${jsonTopLevelLexicalRaw("data", "state")} AS ${identifier("state")},
                   ${jsonArray("data", "body")} AS ${identifier("body")},
                   ${jsonString("data", "firstOperator")} AS ${identifier("first_operator")},
                   ${epochMillis("data", "firstEventTime")} AS ${identifier("first_event_time")},
                   ${epochMillis("data", "createTime")} AS ${identifier("create_time")},
                   ${jsonValue("data", "tags", "Map(String, Array(String))")} AS ${identifier("tags")},
                   ${jsonBool("data", "deleted")} AS ${identifier("deleted")}
            FROM ${qualified(options.consumerDatabase, queueTable)}
            ) COMMENT $consumerComment;
                    """.trimIndent()
                )
                add(
                    """
            $viewCreateClause ${qualified(options.database, table)}${scopeClause()}
            AS (SELECT * FROM ${qualified(options.database, storeTable)} FINAL)
            COMMENT $viewComment;
                    """.trimIndent()
                )
                add(
                    """
            $viewCreateClause ${qualified(options.database, eventTable)}${scopeClause()}
            AS (
            WITH arrayJoin(arrayZip(arrayEnumerate(${identifier("body")}),
                                    ${identifier("body")})) AS ${identifier("events")}
            SELECT ${identifier("id")},
                   ${identifier("context_name")},
                   ${identifier("aggregate_name")},
                   ${identifier("header")},
                   ${identifier("aggregate_id")},
                   ${identifier("tenant_id")},
                   ${identifier("owner_id")},
                   ${identifier("space_id")},
                   ${identifier("command_id")},
                   ${identifier("request_id")},
                   ${identifier("version")},
                   ${identifier("state")},
                   ${identifier("events")}.1 AS ${identifier("event_sequence")},
                   ${jsonTupleValue("events", 2, "id", "String")} AS ${identifier("event_id")},
                   ${jsonTupleValue("events", 2, "name", "String")} AS ${identifier("event_name")},
                   ${jsonTupleValue("events", 2, "revision", "String")} AS ${identifier("event_revision")},
                   ${jsonTupleValue("events", 2, "bodyType", "String")} AS ${identifier("event_body_type")},
                   ${jsonTupleRaw("events", 2, "body")} AS ${identifier("event_body")},
                   ${identifier("first_operator")},
                   ${identifier("first_event_time")},
                   ${identifier("create_time")},
                   ${identifier("tags")},
                   ${identifier("deleted")}
            FROM ${qualified(options.database, table)}
            ) COMMENT $viewComment;
                    """.trimIndent()
                )
            }
        )
    }

    fun renderStateLast(namedAggregate: NamedAggregate): String =
        renderStateLastStatements(namedAggregate).joinToString(STATEMENT_SEPARATOR)

    @Suppress("LongMethod")
    fun renderStateLastStatements(namedAggregate: NamedAggregate): List<String> {
        val aggregate = namedAggregate.toStringWithAlias()
        val storeComment = metadataComment(BiObjectKind.STORE, aggregate)
        val viewComment = metadataComment(BiObjectKind.VIEW, aggregate)
        val consumerComment = metadataComment(BiObjectKind.CONSUMER, aggregate)
        val stateTable = naming.toTableName(namedAggregate, STATE_SUFFIX)
        val stateStoreTable = storageTable(stateTable)
        val table = naming.toTableName(namedAggregate, STATE_LAST_SUFFIX)
        val storeTable = storageTable(table)
        val physicalTable = topology.physicalTableName(storeTable)
        val consumerTable = "${table}_consumer"
        return immutableStatements(
            buildList {
                add(
                    """
            $tableCreateClause ${qualified(options.database, physicalTable)}${scopeClause()}
            (
                ${identifier("id")} String,
                ${identifier("context_name")} String,
                ${identifier("aggregate_name")} String,
                ${identifier("header")} Map(String, String),
                ${identifier("aggregate_id")} String,
                ${identifier("tenant_id")} String,
                ${identifier("owner_id")} String,
                ${identifier("space_id")} String,
                ${identifier("command_id")} String,
                ${identifier("request_id")} String,
                ${identifier("version")} UInt32,
                ${identifier("state")} String,
                ${identifier("body")} Array(String),
                ${identifier("first_operator")} String,
                ${identifier("first_event_time")} DateTime64(3, ${literal(options.timezone)}),
                ${identifier("create_time")} DateTime64(3, ${literal(options.timezone)}),
                ${identifier("tags")} Map(String, Array(String)),
                ${identifier("deleted")} Bool
            ) ${topology.engineSql(ReplacingMergeTreeSpec("version"))}
                  PARTITION BY toYYYYMM(${identifier("first_event_time")})
                  ORDER BY (${identifier("aggregate_id")})
                  COMMENT $storeComment;
                    """.trimIndent()
                )
                topology.distributedFacade(
                    database = options.database,
                    logicalTableName = storeTable,
                    physicalTableName = physicalTable,
                    shardingKey = "sipHash64(${identifier("aggregate_id")})",
                    createIfNotExists = catalogMutationMode == CatalogMutationMode.RECONCILE,
                )?.withTableComment(storeComment)?.let(::add)
                if (catalogMutationMode == CatalogMutationMode.RECONCILE) {
                    add(dropView(options.consumerDatabase, consumerTable))
                }
                add(
                    """
            $materializedViewCreateClause ${qualified(options.consumerDatabase, consumerTable)}${scopeClause()}
            TO ${qualified(options.database, storeTable)}
            AS (
            SELECT *
            FROM ${qualified(options.database, stateStoreTable)}
            ) COMMENT $consumerComment;
                    """.trimIndent()
                )
                add(
                    """
            $viewCreateClause ${qualified(options.database, table)}${scopeClause()}
            AS (SELECT * FROM ${qualified(options.database, storeTable)} FINAL)
            COMMENT $viewComment;
                    """.trimIndent()
                )
            }
        )
    }

    fun renderExpansion(plan: StateExpansionPlan): String =
        renderExpansionStatements(plan).joinToString(STATEMENT_SEPARATOR)

    fun renderExpansionStatements(plan: StateExpansionPlan, aggregate: String = "test.aggregate"): List<String> =
        immutableStatements(plan.views.map { view -> renderExpansionView(view, aggregate) })

    private fun renderExpansionView(view: ExpansionViewPlan, aggregate: String): String {
        val domainWithColumns = view.columns
            .filter { it.placement == ColumnPlacement.WITH }
            .map(::renderColumn)
        val withSql = (view.recovery.cursors.flatMap(::renderCursor) + domainWithColumns)
            .joinToString(",\n")
        val domainSelectColumns = view.columns
            .filter { it.placement == ColumnPlacement.SELECT }
            .map(::renderColumn)
        val selectSql = (domainSelectColumns + renderRecovery(view) + metadataColumns.map(::renderColumn))
            .joinToString(",\n")
        return buildString {
            appendLine(
                "$viewCreateClause ${qualified(options.database, view.targetTableName)}" +
                    "${scopeClause()} AS ("
            )
            if (withSql.isNotBlank()) {
                appendLine("WITH")
                appendLine(withSql)
            }
            appendLine("SELECT")
            appendLine(selectSql)
            appendLine(
                "FROM ${qualified(options.database, view.sourceTableName)} AS ${identifier(SOURCE_ALIAS)}"
            )
            appendLine(") COMMENT ${metadataComment(BiObjectKind.VIEW, aggregate)};")
        }.trimEnd()
    }

    private fun renderColumn(column: ColumnPlan): String =
        "${renderExtraction(column)} AS ${identifier(column.targetName)}"

    private fun renderCursor(cursor: CollectionCursorPlan): List<String> {
        val elements = jsonArray(cursor.source, cursor.property)
        return listOf(
            "arrayJoin(arrayZip(arrayEnumerate($elements),\n" +
                "                   $elements)) AS ${renderReference(cursor.cursor)}",
            "tupleElement(${renderReference(cursor.cursor)}, 2) AS ${renderReference(cursor.element)}",
        )
    }

    private fun renderRecovery(view: ExpansionViewPlan): List<String> = buildList {
        add("${renderReference(ColumnReference.Input(STATE_COLUMN))} AS ${identifier(STATE_TARGET)}")
        view.recovery.currentIndex?.let { currentIndex ->
            add("toUInt64(${renderZeroBasedIndex(currentIndex)}) AS ${identifier(INDEX_TARGET)}")
        }
        add("${renderPointer(view.recovery.pointer)} AS ${identifier(PATH_TARGET)}")
    }

    private fun renderPointer(pointer: List<JsonPointerSegment>): String {
        if (pointer.isEmpty()) {
            return literal("")
        }
        val expressions = pointer.mapIndexed { index, segment ->
            when (segment) {
                is JsonPointerSegment.Property -> {
                    val indexSuffix = if (pointer.getOrNull(index + 1) is JsonPointerSegment.Index) "/" else ""
                    literal("/${segment.encoded}$indexSuffix")
                }

                is JsonPointerSegment.Index -> "toString(${renderZeroBasedIndex(segment.reference)})"
            }
        }
        return "concat(${expressions.joinToString(", ")})"
    }

    private fun renderZeroBasedIndex(reference: ColumnReference): String =
        "tupleElement(${renderReference(reference)}, 1) - 1"

    private fun renderExtraction(column: ColumnPlan): String = when (val extraction = column.extraction) {
        is ColumnExtraction.Reference -> renderReference(extraction.source)
        is ColumnExtraction.JsonValue -> jsonValue(extraction.source, extraction.property, column.type.toSql())
        is ColumnExtraction.JsonString -> jsonString(extraction.source, extraction.property)
        is ColumnExtraction.JsonRaw -> jsonRaw(extraction.source, extraction.property)
        is ColumnExtraction.JsonArray -> jsonArray(extraction.source, extraction.property)
    }

    private fun drop(database: String, table: String): String =
        "DROP TABLE IF EXISTS ${qualified(database, table)}${scopeClause()} SYNC;"

    private fun dropView(database: String, view: String): String =
        "DROP VIEW IF EXISTS ${qualified(database, view)}${scopeClause()} SYNC;"

    private fun storageTable(table: String): String = "${table}_store"

    private fun consumerGroup(consumerTable: String): String {
        return "wow-bi.${consumerIdentity.value}.$consumerTable"
    }

    private fun kafkaSettings(queueTable: String, comment: String): String {
        val settings = buildList {
            if (options.kafkaOffsetStorage == KafkaOffsetStorage.KEEPER) {
                val keeperPath = "${options.kafkaKeeperPathPrefix.trimEnd('/')}/" +
                    "${consumerIdentity.value}/$queueTable"
                add("kafka_keeper_path = ${literal(keeperPath)}")
                val replicaName = when (options.topology) {
                    is ClickHouseTopology.Cluster -> "{replica}"
                    ClickHouseTopology.Standalone -> consumerIdentity.value
                }
                add("kafka_replica_name = ${literal(replicaName)}")
            }
        }
        val engineSettings = settings.takeIf { it.isNotEmpty() }
            ?.let { "SETTINGS ${it.joinToString(",\n                         ")}" }
            .orEmpty()
        val querySettings = if (options.kafkaOffsetStorage == KafkaOffsetStorage.KEEPER) {
            "\nSETTINGS allow_experimental_kafka_offsets_storage_in_keeper = 1"
        } else {
            ""
        }
        return listOf(engineSettings, "COMMENT $comment", querySettings)
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    private fun String.withTableComment(comment: String): String =
        removeSuffix(";") + "\nCOMMENT $comment;"

    private fun immutableStatements(vararg statements: String): List<String> =
        immutableStatements(statements.asList())

    private fun immutableStatements(statements: Collection<String>): List<String> =
        Collections.unmodifiableList(ArrayList(statements))

    private fun qualified(database: String, table: String): String =
        "${identifier(database)}.${identifier(table)}"

    private fun identifier(value: String): String = quoteIdentifier(value)

    private fun literal(value: String): String = stringLiteral(value)

    private fun metadataComment(
        kind: BiObjectKind,
        aggregate: String?,
        phase: BiDeploymentPhase = BiDeploymentPhase.STABLE,
    ): String = literal(
        BiObjectMetadataCodec.encode(
            BiObjectMetadata(
                deploymentId = deployment.deploymentId,
                configurationFingerprint = deployment.configurationFingerprint,
                topologyFingerprint = deployment.topologyFingerprint,
                phase = phase,
                aggregate = aggregate,
                kind = kind,
                consumerIdentity = consumerIdentity.value,
            )
        )
    )

    private fun scopeClause(): String = topology.scopeClause.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()

    private fun jsonValue(source: String, property: String, sqlType: String): String =
        "JSONExtract(${identifier(source)}, ${literal(property)}, ${literal(sqlType)})"

    private fun jsonValue(source: ColumnReference, property: String, sqlType: String): String =
        "JSONExtract(${renderReference(source)}, ${literal(property)}, ${literal(sqlType)})"

    private fun jsonString(source: String, property: String): String =
        "JSONExtractString(${identifier(source)}, ${literal(property)})"

    private fun jsonString(source: ColumnReference, property: String): String =
        "JSONExtractString(${renderReference(source)}, ${literal(property)})"

    private fun jsonRaw(source: String, property: String): String =
        "JSONExtractRaw(${identifier(source)}, ${literal(property)})"

    private fun jsonTopLevelLexicalRaw(source: String, property: String): String {
        val sourceIdentifier = identifier(source)
        val headerProperty = literal("header")
        return "simpleJSONExtractRaw(" +
            "replaceOne($sourceIdentifier, " +
            "concat(${literal("\"header\":")}, simpleJSONExtractRaw($sourceIdentifier, $headerProperty)), " +
            "${literal("\"header\":{}")}), ${literal(property)})"
    }

    private fun jsonRaw(source: ColumnReference, property: String): String =
        "JSONExtractRaw(${renderReference(source)}, ${literal(property)})"

    private fun jsonArray(source: String, property: String): String =
        "JSONExtractArrayRaw(${identifier(source)}, ${literal(property)})"

    private fun jsonArray(source: ColumnReference, property: String): String =
        "JSONExtractArrayRaw(${renderReference(source)}, ${literal(property)})"

    private fun renderReference(reference: ColumnReference): String = when (reference) {
        is ColumnReference.Input -> "${identifier(SOURCE_ALIAS)}.${identifier(reference.name)}"
        is ColumnReference.Alias -> identifier(reference.name)
    }

    private fun jsonUInt(source: String, property: String): String =
        "JSONExtractUInt(${identifier(source)}, ${literal(property)})"

    private fun jsonInt(source: String, property: String): String =
        "JSONExtractInt(${identifier(source)}, ${literal(property)})"

    private fun jsonBool(source: String, property: String): String =
        "JSONExtractBool(${identifier(source)}, ${literal(property)})"

    private fun jsonTupleValue(
        source: String,
        tupleIndex: Int,
        property: String,
        sqlType: String,
    ): String =
        "JSONExtract(${identifier(source)}.$tupleIndex, ${literal(property)}, ${literal(sqlType)})"

    private fun jsonTupleRaw(source: String, tupleIndex: Int, property: String): String =
        "JSONExtractRaw(${identifier(source)}.$tupleIndex, ${literal(property)})"

    private val tableCreateClause: String
        get() = when (catalogMutationMode) {
            CatalogMutationMode.RECONCILE -> "CREATE TABLE IF NOT EXISTS"
            CatalogMutationMode.CREATE_ONLY -> "CREATE TABLE"
        }

    private val materializedViewCreateClause: String
        get() = when (catalogMutationMode) {
            CatalogMutationMode.RECONCILE -> "CREATE MATERIALIZED VIEW IF NOT EXISTS"
            CatalogMutationMode.CREATE_ONLY -> "CREATE MATERIALIZED VIEW"
        }

    private val viewCreateClause: String
        get() = when (catalogMutationMode) {
            CatalogMutationMode.RECONCILE -> "CREATE OR REPLACE VIEW"
            CatalogMutationMode.CREATE_ONLY -> "CREATE VIEW"
        }

    private fun isQueueRetained(queueTable: String): Boolean =
        BiObjectKey(options.consumerDatabase, queueTable) in retainedQueueKeys

    private fun epochMillis(source: String, property: String): String =
        "toDateTime64(${jsonInt(source, property)} / 1000.0, 3, ${literal(options.timezone)})"

    companion object {
        const val DEPLOYMENT_ANCHOR = "__wow_bi_deployment"
        const val COMMAND_SUFFIX = "command"
        const val STATE_SUFFIX = "state"
        const val STATE_LAST_SUFFIX = "state_last"
        const val STATE_COLUMN = "state"
        const val STATE_TARGET = "__state"
        const val PATH_TARGET = "__path"
        const val INDEX_TARGET = "__index"
        const val SOURCE_ALIAS = "__source"
        const val STATEMENT_SEPARATOR = "\n\n"
        const val COMMAND_PUBLIC_STATEMENT_COUNT: Int = 1
        const val STATE_PUBLIC_STATEMENT_COUNT: Int = 2
    }
}

private fun buildMetadataColumns(timezone: String): List<ColumnPlan> = listOf(
    metadataColumn("id", ClickHouseType.String),
    metadataColumn("aggregate_id", ClickHouseType.String),
    metadataColumn("tenant_id", ClickHouseType.String),
    metadataColumn("owner_id", ClickHouseType.String),
    metadataColumn("space_id", ClickHouseType.String),
    metadataColumn("command_id", ClickHouseType.String),
    metadataColumn("request_id", ClickHouseType.String),
    metadataColumn("version", ClickHouseType.UInt32),
    metadataColumn("first_operator", ClickHouseType.String),
    metadataColumn("first_event_time", ClickHouseType.DateTime64(3, timezone)),
    metadataColumn("create_time", ClickHouseType.DateTime64(3, timezone)),
    metadataColumn(
        "tags",
        ClickHouseType.Map(
            ClickHouseType.String,
            ClickHouseType.Array(ClickHouseType.String),
        ),
    ),
    metadataColumn("deleted", ClickHouseType.Bool),
)

private fun metadataColumn(name: String, type: ClickHouseType): ColumnPlan = ColumnPlan(
    name = name,
    path = name,
    targetName = "__$name",
    type = type,
    extraction = ColumnExtraction.Reference(ColumnReference.Input(name)),
    placement = ColumnPlacement.SELECT,
)
