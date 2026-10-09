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

import me.ahoo.wow.bi.layout.BiStoreSchema

/** Renders a store from its [BiStoreSchema]: the table, plus its Distributed facade on a cluster. */
internal fun ClickHouseRenderContext.renderStoreStatements(
    schema: BiStoreSchema,
    store: String,
    comment: String,
): List<String> {
    val physicalTable = layout.physicalStore(store)
    return buildList {
        add(renderStoreTable(schema, physicalTable, comment))
        topology.distributedFacade(
            DistributedFacadeSpec(
                database = options.database,
                logicalTableName = store,
                physicalTableName = physicalTable,
                shardingKey = "sipHash64(${schema.shardingColumns.joinToString(", ", transform = ::identifier)})",
                createIfNotExists = catalogMutationMode == CatalogMutationMode.RECONCILE,
            )
        )?.withTableComment(comment)?.let(::add)
    }
}

private fun ClickHouseRenderContext.renderStoreTable(
    schema: BiStoreSchema,
    physicalTable: String,
    comment: String,
): String {
    val columns = schema.columns(storeDateTimeType())
        .joinToString(",\n") { column -> "    ${identifier(column.name)} ${column.type}" }
    val orderBy = schema.orderBy.singleOrNull()?.let(::identifier)
        ?: schema.orderBy.joinToString(", ", prefix = "(", postfix = ")", transform = ::identifier)
    return "$tableCreateClause ${qualified(options.database, physicalTable)}${scopeClause()}\n" +
        "(\n$columns\n" +
        ") ${topology.engineSql(ReplacingMergeTreeSpec(schema.versionColumn))}\n" +
        "  PARTITION BY toYYYYMM(${identifier(schema.partitionColumn)})\n" +
        "  ORDER BY $orderBy\n" +
        "  COMMENT $comment;"
}

/** The `DateTime64(3, '<timezone>')` type every store's time columns use. */
internal fun ClickHouseRenderContext.storeDateTimeType(): String = storeDateTimeType(options.timezone)

internal fun storeDateTimeType(timezone: String): String = "DateTime64(3, ${literal(timezone)})"
