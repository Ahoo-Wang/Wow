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

internal class ClickHouseCommandRenderer(private val context: ClickHouseRenderContext) {
    fun expectedQueries(namedAggregate: NamedAggregate): Map<BiObjectKey, ExpectedBiQuery> = with(context) {
        val table = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.COMMAND_SUFFIX)
        val storeTable = storageTable(table)
        val queueTable = "${table}_queue"
        mapOf(
            BiObjectKey(options.consumerDatabase, "${table}_consumer") to ExpectedBiQuery(
                selectSql = renderConsumerSelect(queueTable),
                target = BiObjectKey(options.database, storeTable),
            ),
            BiObjectKey(options.database, table) to ExpectedBiQuery(renderPublicViewSelect(storeTable)),
        )
    }

    fun render(namedAggregate: NamedAggregate): ClickHouseStreamRenderPlan = with(context) {
        val aggregate = namedAggregate.toStringWithAlias()
        val table = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.COMMAND_SUFFIX)
        val storeTable = storageTable(table)
        val physicalTable = topology.physicalTableName(storeTable)
        val queueTable = "${table}_queue"
        val consumerTable = "${table}_consumer"
        val topic = naming.toTopicName(namedAggregate, ClickHouseScriptRenderer.COMMAND_SUFFIX)
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
                            shardingKey = "sipHash64(${identifier("aggregate_id")})",
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
                listOf(renderPublicView(table, storeTable, viewComment))
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
            (${identifier("data")} String)
            ENGINE = Kafka(${literal(options.kafkaBootstrapServers)}, ${literal(topic)},
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
        val aggregateVersionJson = jsonValue("data", "aggregateVersion", "Nullable(UInt32)")
        """
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
        """.trimIndent()
    }

    private fun renderPublicView(table: String, storeTable: String, comment: String): String = with(context) {
        """
            $viewCreateClause ${qualified(options.database, table)}${scopeClause()}
            AS (${renderPublicViewSelect(storeTable)})
            COMMENT $comment;
        """.trimIndent()
    }

    private fun renderPublicViewSelect(storeTable: String): String = with(context) {
        "SELECT * FROM ${qualified(options.database, storeTable)} FINAL"
    }
}
