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

internal class ClickHouseStateLastRenderer(private val context: ClickHouseRenderContext) {
    fun expectedQueries(namedAggregate: NamedAggregate): Map<BiObjectKey, ExpectedBiQuery> = with(context) {
        val statePhysicalBase =
            naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val stateStoreTable = storageTable(statePhysicalBase)
        val table = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_LAST_SUFFIX)
        val physicalBase =
            naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_LAST_SUFFIX)
        val storeTable = storageTable(physicalBase)
        mapOf(
            BiObjectKey(options.consumerDatabase, "${physicalBase}_consumer") to ExpectedBiQuery(
                selectSql = renderConsumerSelect(stateStoreTable),
                target = BiObjectKey(options.database, storeTable),
            ),
            BiObjectKey(options.database, table) to ExpectedBiQuery(renderPublicViewSelect(storeTable)),
        )
    }

    fun render(namedAggregate: NamedAggregate): List<String> = with(context) {
        val aggregate = namedAggregate.toStringWithAlias()
        val statePhysicalBase =
            naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_SUFFIX)
        val stateStoreTable = storageTable(statePhysicalBase)
        val table = naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_LAST_SUFFIX)
        val physicalBase =
            naming.toTableName(namedAggregate, ClickHouseScriptRenderer.STATE_LAST_SUFFIX)
        val storeTable = storageTable(physicalBase)
        val physicalTable = topology.physicalTableName(storeTable)
        val consumerTable = "${physicalBase}_consumer"
        val storeComment = metadataComment(BiObjectKind.STORE, aggregate)
        val viewComment = metadataComment(BiObjectKind.VIEW, aggregate)
        val consumerComment = metadataComment(BiObjectKind.CONSUMER, aggregate)
        immutableStatements(
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
                if (catalogMutationMode == CatalogMutationMode.RECONCILE) {
                    add(dropView(options.consumerDatabase, consumerTable))
                }
                add(renderConsumer(consumerTable, storeTable, stateStoreTable, consumerComment))
                add(renderPublicView(table, storeTable, viewComment))
            }
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
                  PARTITION BY toYYYYMM(${identifier("first_event_time")})
                  ORDER BY (${identifier("tenant_id")}, ${identifier("aggregate_id")})
                  COMMENT $comment;
        """.trimIndent()
    }

    private fun renderConsumer(
        consumerTable: String,
        storeTable: String,
        stateStoreTable: String,
        comment: String,
    ): String = with(context) {
        buildString {
            appendLine(
                "$materializedViewCreateClause ${qualified(options.consumerDatabase, consumerTable)}${scopeClause()}"
            )
            appendLine("TO ${qualified(options.database, storeTable)}")
            appendLine("AS (")
            appendLine(renderConsumerSelect(stateStoreTable))
            append(") COMMENT $comment;")
        }
    }

    private fun renderConsumerSelect(stateStoreTable: String): String = with(context) {
        """
            SELECT *
            FROM ${qualified(options.database, stateStoreTable)}
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
