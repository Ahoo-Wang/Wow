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
import me.ahoo.wow.bi.layout.BiStoreSchema
import me.ahoo.wow.modeling.toStringWithAlias

internal class ClickHouseStateLastRenderer(private val context: ClickHouseRenderContext) {
    fun expectedQueries(namedAggregate: NamedAggregate): Map<BiObjectKey, ExpectedBiQuery> = with(context) {
        val aggregateLayout = layout.of(namedAggregate)
        val stateStoreTable = aggregateLayout.state.store
        val table = aggregateLayout.stateLast
        val storeTable = aggregateLayout.stateLastStore
        mapOf(
            layout.ingressKey(aggregateLayout.stateLastConsumer) to ExpectedBiQuery(
                selectSql = renderConsumerSelect(stateStoreTable),
                target = BiObjectKey(options.database, storeTable),
            ),
            BiObjectKey(options.database, table) to ExpectedBiQuery(renderPublicViewSelect(storeTable)),
        )
    }

    fun render(namedAggregate: NamedAggregate): List<String> = with(context) {
        val aggregate = namedAggregate.toStringWithAlias()
        val aggregateLayout = layout.of(namedAggregate)
        val stateStoreTable = aggregateLayout.state.store
        val table = aggregateLayout.stateLast
        val storeTable = aggregateLayout.stateLastStore
        val consumerTable = aggregateLayout.stateLastConsumer
        val storeComment = metadataComment(BiObjectKind.STORE, aggregate)
        val viewComment = metadataComment(BiObjectKind.VIEW, aggregate)
        val consumerComment = metadataComment(BiObjectKind.CONSUMER, aggregate)
        immutableStatements(
            buildList {
                addAll(renderStoreStatements(BiStoreSchema.STATE_LAST, storeTable, storeComment))
                val consumerKey = layout.ingressKey(consumerTable)
                if (replaces(consumerKey)) {
                    // Only while state ingress is paused: the state consumer feeds this one through the state store.
                    add(dropView(options.consumerDatabase, consumerTable))
                }
                if (renders(consumerKey)) {
                    add(renderConsumer(consumerTable, storeTable, stateStoreTable, consumerComment))
                }
                if (renders(layout.viewKey(table))) {
                    add(renderPublicView(table, storeTable, viewComment))
                }
            }
        )
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
