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
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.bi.expansion.plan.ColumnExtraction
import me.ahoo.wow.bi.expansion.plan.ColumnPlacement
import me.ahoo.wow.bi.expansion.plan.ColumnPlan
import me.ahoo.wow.bi.expansion.plan.ColumnReference
import me.ahoo.wow.bi.expansion.plan.ExpansionViewPlan
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.quoteIdentifier
import me.ahoo.wow.bi.renderer.ClickHouseSqlSyntax.stringLiteral
import me.ahoo.wow.bi.type.ClickHouseType

internal class ClickHouseScriptRenderer(private val options: BiScriptOptions = BiScriptOptions()) {
    private val naming = BiTableNaming(options)

    fun renderGlobal(): String =
        """
            CREATE DATABASE IF NOT EXISTS ${identifier(options.database)} ${onCluster()};
            CREATE DATABASE IF NOT EXISTS ${identifier(options.consumerDatabase)} ${onCluster()};
        """.trimIndent()

    fun renderClear(namedAggregate: NamedAggregate, expansionTables: List<String>): String {
        val commandTable = naming.toDistributedTableName(namedAggregate, COMMAND_SUFFIX)
        val stateTable = naming.toDistributedTableName(namedAggregate, STATE_SUFFIX)
        val stateLastTable = naming.toDistributedTableName(namedAggregate, STATE_LAST_SUFFIX)
        return buildString {
            appendLine("------------------command------------------")
            appendDrop(options.database, commandTable)
            appendDrop(options.database, "${commandTable}_local")
            appendDrop(options.consumerDatabase, "${commandTable}_queue")
            appendDrop(options.consumerDatabase, "${commandTable}_consumer")
            appendLine("------------------command------------------")
            appendLine("------------------state------------------")
            appendDrop(options.database, stateTable)
            appendDrop(options.database, "${stateTable}_event")
            appendDrop(options.database, "${stateTable}_local")
            appendDrop(options.consumerDatabase, "${stateTable}_queue")
            appendDrop(options.consumerDatabase, "${stateTable}_consumer")
            appendLine("------------------state------------------")
            appendLine("------------------stateLast------------------")
            appendDrop(options.database, stateLastTable)
            appendDrop(options.database, "${stateLastTable}_local")
            appendDrop(options.consumerDatabase, "${stateLastTable}_consumer")
            appendLine("------------------stateLast------------------")
            appendLine("------------------expansion------------------")
            expansionTables.forEach { appendDrop(options.database, it) }
            appendLine("------------------expansion------------------")
        }
    }

    @Suppress("LongMethod")
    fun renderCommand(namedAggregate: NamedAggregate): String {
        val table = naming.toDistributedTableName(namedAggregate, COMMAND_SUFFIX)
        val localTable = "${table}_local"
        val queueTable = "${table}_queue"
        val consumerTable = "${table}_consumer"
        val topic = naming.toTopicName(namedAggregate, COMMAND_SUFFIX)
        return """
            CREATE TABLE IF NOT EXISTS ${qualified(options.database, localTable)} ${onCluster()}
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
                ${identifier("allow_create")} Bool,
                ${identifier("body_type")} String,
                ${identifier("body")} String,
                ${identifier("create_time")} DateTime(${literal(options.timezone)})
            ) ENGINE = ReplicatedMergeTree(
                    ${replicatedTablePath()}, ${literal(options.replica)})
                  PARTITION BY toYYYYMM(${identifier("create_time")})
                  ORDER BY ${identifier("id")}
            ;

            CREATE TABLE IF NOT EXISTS ${qualified(options.database, table)} ${onCluster()}
                AS ${qualified(options.database, localTable)}
                    ENGINE = Distributed(${literal(
            options.cluster
        )}, ${identifier(options.database)}, ${literal(localTable)}, sipHash64(${identifier("aggregate_id")}));

            CREATE TABLE IF NOT EXISTS ${qualified(options.consumerDatabase, queueTable)} ${onCluster()}
            (
                ${identifier("data")} String
            ) ENGINE = Kafka(${literal(
            options.kafkaBootstrapServers
        )}, ${literal(topic)}, ${literal("clickhouse_$consumerTable")}, ${literal("JSONAsString")});

            CREATE MATERIALIZED VIEW IF NOT EXISTS ${qualified(options.consumerDatabase, consumerTable)}
                        ${onCluster()}
                        TO ${qualified(options.database, table)}
            AS
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
                   ${jsonValue("data", "aggregateVersion", "Nullable(UInt32)")} AS ${identifier("aggregate_version")},
                   ${jsonBool("data", "isCreate")} AS ${identifier("is_create")},
                   ${jsonBool("data", "allowCreate")} AS ${identifier("allow_create")},
                   ${jsonString("data", "bodyType")} AS ${identifier("body_type")},
                   ${jsonString("data", "body")} AS ${identifier("body")},
                   ${epochMillis("data", "createTime")} AS ${identifier("create_time")}
            FROM ${qualified(options.consumerDatabase, queueTable)}
            ;
        """.trimIndent()
    }

    @Suppress("LongMethod")
    fun renderStateEvent(namedAggregate: NamedAggregate): String {
        val table = naming.toDistributedTableName(namedAggregate, STATE_SUFFIX)
        val localTable = "${table}_local"
        val eventTable = "${table}_event"
        val queueTable = "${table}_queue"
        val consumerTable = "${table}_consumer"
        val topic = naming.toTopicName(namedAggregate, STATE_SUFFIX)
        return """
            CREATE TABLE IF NOT EXISTS ${qualified(options.database, localTable)} ${onCluster()}
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
                ${identifier("first_event_time")} DateTime(${literal(options.timezone)}),
                ${identifier("create_time")} DateTime(${literal(options.timezone)}),
                ${identifier("tags")} Map(String, Array(String)),
                ${identifier("deleted")} Bool
            ) ENGINE = ReplicatedReplacingMergeTree(${replicatedTablePath()},
                                                    ${literal(options.replica)}, ${identifier("version")})
                  PARTITION BY toYYYYMM(${identifier("create_time")})
                  ORDER BY (${identifier("aggregate_id")}, ${identifier("version")})
            ;

            CREATE TABLE IF NOT EXISTS ${qualified(options.database, table)} ${onCluster()}
                AS ${qualified(options.database, localTable)}
                    ENGINE = Distributed(${literal(
            options.cluster
        )}, ${identifier(options.database)}, ${literal(localTable)}, sipHash64(${identifier("aggregate_id")}));

            CREATE TABLE IF NOT EXISTS ${qualified(options.consumerDatabase, queueTable)} ${onCluster()}
            (
                ${identifier("data")} String
            ) ENGINE = Kafka(${literal(
            options.kafkaBootstrapServers
        )}, ${literal(topic)}, ${literal("clickhouse_$consumerTable")}, ${literal("JSONAsString")});

            CREATE MATERIALIZED VIEW IF NOT EXISTS ${qualified(options.consumerDatabase, consumerTable)}
                        ${onCluster()}
                        TO ${qualified(options.database, table)}
            AS
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
                   ${jsonString("data", "state")} AS ${identifier("state")},
                   ${jsonArray("data", "body")} AS ${identifier("body")},
                   ${jsonString("data", "firstOperator")} AS ${identifier("first_operator")},
                   ${epochMillis("data", "firstEventTime")} AS ${identifier("first_event_time")},
                   ${epochMillis("data", "createTime")} AS ${identifier("create_time")},
                   ${jsonValue("data", "tags", "Map(String, Array(String))")} AS ${identifier("tags")},
                   ${jsonBool("data", "deleted")} AS ${identifier("deleted")}
            FROM ${qualified(options.consumerDatabase, queueTable)}
            ;

            CREATE VIEW IF NOT EXISTS ${qualified(options.database, eventTable)} ${onCluster()}
            AS
            WITH arrayJoin(arrayZip(arrayEnumerate(${identifier(
            "body"
        )}), ${identifier("body")})) AS ${identifier("events")}
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
                   ${jsonTupleValue("events", 2, "body", "String")} AS ${identifier("event_body")},
                   ${identifier("first_operator")},
                   ${identifier("first_event_time")},
                   ${identifier("create_time")},
                   ${identifier("tags")},
                   ${identifier("deleted")}
            FROM ${qualified(options.database, table)};
        """.trimIndent()
    }

    fun renderStateLast(namedAggregate: NamedAggregate): String {
        val stateTable = naming.toDistributedTableName(namedAggregate, STATE_SUFFIX)
        val table = naming.toDistributedTableName(namedAggregate, STATE_LAST_SUFFIX)
        val localTable = "${table}_local"
        val consumerTable = "${table}_consumer"
        return """
            CREATE TABLE IF NOT EXISTS ${qualified(options.database, localTable)} ${onCluster()}
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
                ${identifier("first_event_time")} DateTime(${literal(options.timezone)}),
                ${identifier("create_time")} DateTime(${literal(options.timezone)}),
                ${identifier("tags")} Map(String, Array(String)),
                ${identifier("deleted")} Bool
            ) ENGINE = ReplicatedReplacingMergeTree(${replicatedTablePath()},
                                                    ${literal(options.replica)}, ${identifier("version")})
                  PARTITION BY toYYYYMM(${identifier("first_event_time")})
                  ORDER BY (${identifier("aggregate_id")})
            ;

            CREATE TABLE IF NOT EXISTS ${qualified(options.database, table)} ${onCluster()}
                AS ${qualified(options.database, localTable)}
                    ENGINE = Distributed(${literal(
            options.cluster
        )}, ${identifier(options.database)}, ${literal(localTable)}, sipHash64(${identifier("aggregate_id")}));

            CREATE MATERIALIZED VIEW IF NOT EXISTS ${qualified(options.consumerDatabase, consumerTable)}
                        ${onCluster()}
                        TO ${qualified(options.database, table)}
            AS
            SELECT *
            FROM ${qualified(options.database, stateTable)}
            ;
        """.trimIndent()
    }

    fun renderExpansion(plan: StateExpansionPlan): String =
        renderExpansionStatements(plan).joinToString("\n")

    fun renderExpansionStatements(plan: StateExpansionPlan): List<String> =
        plan.views.map(::renderExpansionView)

    private fun renderExpansionView(view: ExpansionViewPlan): String {
        val withSql = view.columns
            .filter { it.placement == ColumnPlacement.WITH }
            .joinToString(",\n", transform = ::renderColumn)
        val selectSql = view.columns
            .filter { it.placement == ColumnPlacement.SELECT }
            .plus(metadataColumns)
            .joinToString(",\n", transform = ::renderColumn)
        return buildString {
            appendLine(
                "CREATE VIEW IF NOT EXISTS ${qualified(options.database, view.targetTableName)} ${onCluster()} AS"
            )
            if (withSql.isNotBlank()) {
                appendLine("WITH")
                appendLine(withSql)
            }
            appendLine("SELECT")
            appendLine(selectSql)
            appendLine(
                "FROM ${qualified(options.database, view.sourceTableName)} AS ${identifier(SOURCE_ALIAS)};"
            )
        }.trimEnd()
    }

    private fun renderColumn(column: ColumnPlan): String =
        "${renderExtraction(column)} AS ${identifier(column.targetName)}"

    private fun renderExtraction(column: ColumnPlan): String = when (val extraction = column.extraction) {
        is ColumnExtraction.Reference -> renderReference(extraction.source)
        is ColumnExtraction.JsonValue -> jsonValue(extraction.source, extraction.property, column.type.toSql())
        is ColumnExtraction.JsonString -> jsonString(extraction.source, extraction.property)
        is ColumnExtraction.JsonRaw -> jsonRaw(extraction.source, extraction.property)
        is ColumnExtraction.JsonArray -> jsonArray(extraction.source, extraction.property)
        is ColumnExtraction.ArrayJoin -> "arrayJoin(${jsonArray(extraction.source, extraction.property)})"
    }

    private fun StringBuilder.appendDrop(database: String, table: String) {
        appendLine("DROP TABLE IF EXISTS ${qualified(database, table)} ${onCluster()} SYNC;")
    }

    private fun qualified(database: String, table: String): String =
        "${identifier(database)}.${identifier(table)}"

    private fun identifier(value: String): String = quoteIdentifier(value)

    private fun literal(value: String): String = stringLiteral(value)

    private fun onCluster(): String = "ON CLUSTER ${literal(options.cluster)}"

    private fun replicatedTablePath(): String = literal(
        "/clickhouse/${options.installation}/${options.cluster}/tables/${options.shard}/{database}/{table}"
    )

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

    private fun jsonBool(source: String, property: String): String =
        "JSONExtractBool(${identifier(source)}, ${literal(property)})"

    private fun jsonTupleValue(
        source: String,
        tupleIndex: Int,
        property: String,
        sqlType: String,
    ): String =
        "JSONExtract(${identifier(source)}.$tupleIndex, ${literal(property)}, ${literal(sqlType)})"

    private fun epochMillis(source: String, property: String): String =
        "toDateTime64(${jsonUInt(source, property)} / 1000.0, 3, ${literal(options.timezone)})"

    private val metadataColumns: List<ColumnPlan> = listOf(
        metadataColumn("id", ClickHouseType.String),
        metadataColumn("aggregate_id", ClickHouseType.String),
        metadataColumn("tenant_id", ClickHouseType.String),
        metadataColumn("owner_id", ClickHouseType.String),
        metadataColumn("space_id", ClickHouseType.String),
        metadataColumn("command_id", ClickHouseType.String),
        metadataColumn("request_id", ClickHouseType.String),
        metadataColumn("version", ClickHouseType.UInt32),
        metadataColumn("first_operator", ClickHouseType.String),
        metadataColumn("first_event_time", ClickHouseType.DateTime(options.timezone)),
        metadataColumn("create_time", ClickHouseType.DateTime(options.timezone)),
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

    private companion object {
        const val COMMAND_SUFFIX = "command"
        const val STATE_SUFFIX = "state"
        const val STATE_LAST_SUFFIX = "state_last"
        const val SOURCE_ALIAS = "__source"
    }
}
