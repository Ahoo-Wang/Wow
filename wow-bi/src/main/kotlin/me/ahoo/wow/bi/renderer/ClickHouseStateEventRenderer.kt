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
import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.ExpectedBiQuery
import me.ahoo.wow.modeling.toStringWithAlias

/** Keeps emitted DDL and drift-manifest SQL on the same SELECT builders. */
@Suppress("TooManyFunctions")
internal class ClickHouseStateEventRenderer(private val context: ClickHouseRenderContext) {
    fun expectedQueries(namedAggregate: NamedAggregate): Map<BiObjectKey, ExpectedBiQuery> = with(context) {
        val table = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val physicalBase = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val storeTable = storageTable(physicalBase)
        val eventTable = "${table}_event"
        val queueTable = "${physicalBase}_queue"
        mapOf(
            BiObjectKey(options.consumerDatabase, "${physicalBase}_consumer") to ExpectedBiQuery(
                selectSql = renderConsumerSelect(queueTable),
                target = BiObjectKey(options.database, storeTable),
            ),
            BiObjectKey(options.database, table) to ExpectedBiQuery(renderStateViewSelect(storeTable)),
            BiObjectKey(options.database, eventTable) to ExpectedBiQuery(renderEventViewSelect(table)),
        )
    }

    fun render(namedAggregate: NamedAggregate): ClickHouseStreamRenderPlan = with(context) {
        val aggregate = namedAggregate.toStringWithAlias()
        val table = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val physicalBase = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val storeTable = storageTable(physicalBase)
        val physicalTable = topology.physicalTableName(storeTable)
        val eventTable = "${table}_event"
        val queueTable = "${physicalBase}_queue"
        val consumerTable = "${physicalBase}_consumer"
        val topic = naming.toTopicName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val storeComment = metadataComment(BiObjectKind.STORE, aggregate)
        val viewComment = metadataComment(BiObjectKind.VIEW, aggregate)
        val queueComment = metadataComment(BiObjectKind.QUEUE, aggregate)
        val consumerComment = metadataComment(BiObjectKind.CONSUMER, aggregate)
        ClickHouseStreamRenderPlan(
            storage = immutableStatements(
                buildList {
                    add(renderStore(physicalTable, storeComment))
                    topology.distributedFacade(
                        DistributedFacadeSpec(
                            database = options.database,
                            logicalTableName = storeTable,
                            physicalTableName = physicalTable,
                            shardingKey = "sipHash64(${identifier("tenant_id")}, ${identifier("aggregate_id")})",
                            createIfNotExists = catalogMutationMode == CatalogMutationMode.RECONCILE,
                        )
                    )?.withTableComment(storeComment)?.let(::add)
                }
            ),
            ingress = immutableStatements(
                buildList {
                    if (catalogMutationMode == CatalogMutationMode.RECONCILE) {
                        add(dropView(options.consumerDatabase, consumerTable))
                    }
                    if (!isQueueRetained(queueTable)) {
                        add(renderQueue(queueTable, topic, consumerTable, queueComment))
                    }
                    add(renderConsumer(consumerTable, storeTable, queueTable, consumerComment))
                }
            ),
            publicViews = immutableStatements(
                listOf(
                    renderStateView(table, storeTable, viewComment),
                    renderEventView(eventTable, table, viewComment),
                )
            ),
        )
    }

    private fun renderStore(physicalTable: String, comment: String): String = with(context) {
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
                  ORDER BY (${identifier("tenant_id")}, ${identifier("aggregate_id")}, ${identifier("version")})
                  COMMENT $comment;
        """.trimIndent()
    }

    private fun renderQueue(
        queueTable: String,
        topic: String,
        consumerTable: String,
        comment: String,
    ): String = with(context) {
        """
            CREATE TABLE ${qualified(options.consumerDatabase, queueTable)}${scopeClause()}
            (
                ${identifier("data")} String
            ) ENGINE = Kafka(${literal(options.kafkaBootstrapServers)}, ${literal(topic)},
                             ${literal(consumerGroup(consumerTable))}, ${literal("JSONAsString")})
            ${kafkaSettings(queueTable, comment)};
        """.trimIndent()
    }

    private fun renderConsumer(
        consumerTable: String,
        storeTable: String,
        queueTable: String,
        comment: String,
    ): String = with(context) {
        buildString {
            appendLine(
                "$materializedViewCreateClause ${qualified(options.consumerDatabase, consumerTable)}${scopeClause()}"
            )
            appendLine("TO ${qualified(options.database, storeTable)}")
            appendLine("AS (")
            appendLine(renderConsumerSelect(queueTable))
            append(") COMMENT $comment;")
        }
    }

    private fun renderConsumerSelect(queueTable: String): String = with(context) {
        """
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
        """.trimIndent()
    }

    private fun renderStateView(table: String, storeTable: String, comment: String): String = with(context) {
        """
            $viewCreateClause ${qualified(options.database, table)}${scopeClause()}
            AS (${renderStateViewSelect(storeTable)})
            COMMENT $comment;
        """.trimIndent()
    }

    private fun renderStateViewSelect(storeTable: String): String = with(context) {
        "SELECT * FROM ${qualified(options.database, storeTable)} FINAL"
    }

    private fun renderEventView(eventTable: String, stateTable: String, comment: String): String = with(context) {
        buildString {
            appendLine("$viewCreateClause ${qualified(options.database, eventTable)}${scopeClause()}")
            appendLine("AS (")
            appendLine(renderEventViewSelect(stateTable))
            append(") COMMENT $comment;")
        }
    }

    private fun renderEventViewSelect(stateTable: String): String = with(context) {
        """
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
            FROM ${qualified(options.database, stateTable)}
        """.trimIndent()
    }
}
