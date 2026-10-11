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

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.query.schema.QueryDatasetProperties
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.node.ObjectNode

/**
 * A dataset declaration drafted from a table: its [document], in the format of a dataset file, and the columns it
 * leaves out because a dataset cannot declare their type.
 */
data class ClickHouseDatasetDraft(val document: ObjectNode, val skipped: List<ClickHouseColumn>)

/**
 * Drafts a dataset declaration from what `system.columns` proves about a table, with the rules
 * [ClickHouseQuerySchemaAdapter] checks a declaration by, so a draft always passes that check (dataset platform design
 * §4.1). What only a person can decide is left for them:
 *
 * - the scope and the identity are suggested from the conventional column names (`tenant_id`, `owner_id`,
 *   `space_id`, `id`, and wow-bi's `__`-prefixed ones) only when such a column can hold them;
 * - no field is declared sensitive: whether one is, is a person's decision, which the registry requires before it
 *   publishes the dataset.
 */
object ClickHouseDatasetDrafts {
    private val TENANT = listOf("tenant_id", "__tenant_id")
    private val OWNER = listOf("owner_id", "__owner_id")
    private val SPACE = listOf("space_id", "__space_id")
    private val IDENTITY = listOf("id", "__aggregate_id")
    private const val TEMPORAL_DATE = "TEMPORAL_DATE"

    /** The draft of [table], read from the named [source] (the default one when `null`). */
    @JvmOverloads
    fun draft(table: ClickHouseTable, source: String? = null): ClickHouseDatasetDraft {
        val (supported, skipped) = table.columns.values.partition { it.family != ClickHouseTypeFamily.UNSUPPORTED }
        val document = JsonSerializer.createObjectNode()
        document.put(QueryDatasetProperties.TITLE, table.name)
        fun suggest(names: List<String>): String? = names.firstOrNull { name ->
            supported.any { it.name == name && it.key }
        }
        suggest(IDENTITY)?.let { document.put(QueryDatasetProperties.IDENTITY, it) }
        val scope = document.putObject(QueryDatasetProperties.SCOPE)
        suggest(TENANT)?.let { scope.put(QueryDatasetProperties.TENANT, it) }
        suggest(OWNER)?.let { scope.put(QueryDatasetProperties.OWNER, it) }
        suggest(SPACE)?.let { scope.put(QueryDatasetProperties.SPACE, it) }
        val storage = document.putObject(QueryDatasetProperties.STORAGE)
            .put(ClickHouseDatasetStorage.TABLE, "${table.database}.${table.name}")
        source?.let { storage.put(ClickHouseDatasetStorage.SOURCE, it) }
        if (table.supportsFinal) {
            storage.put(ClickHouseDatasetStorage.DEDUPLICATE, ClickHouseDatasetStorage.Deduplicate.FINAL.name)
        }
        val fields = document.putObject(QuerySchemaDeclarationProperties.FIELDS)
        supported.forEach { fields.set(it.name, it.declaration()) }
        return ClickHouseDatasetDraft(document, skipped)
    }

    /** Whether the column can be a scope or identity column: one non-`Nullable` scalar string or integer. */
    private val ClickHouseColumn.key: Boolean
        get() = !nullable && (family == ClickHouseTypeFamily.STRING || family == ClickHouseTypeFamily.INTEGER)

    private fun ClickHouseColumn.declaration(): ObjectNode {
        val declaration = JsonSerializer.createObjectNode()
        if (array) {
            declaration.put(QuerySchemaDeclarationProperties.KIND, "ARRAY")
            declaration.putObject(QuerySchemaDeclarationProperties.ITEMS)
                .putArray(QuerySchemaDeclarationProperties.TYPES).add("STRING")
        } else {
            declaration.putArray(QuerySchemaDeclarationProperties.TYPES).add(valueType())
            if (temporal) declaration.putObject(QuerySchemaDeclarationProperties.SEMANTIC).put("type", TEMPORAL_DATE)
        }
        declaration.put(QuerySchemaDeclarationProperties.NULLABLE, nullable)
        return declaration
    }

    private fun ClickHouseColumn.valueType(): String = when (family) {
        ClickHouseTypeFamily.INTEGER -> "INTEGER"
        ClickHouseTypeFamily.FLOAT, ClickHouseTypeFamily.DECIMAL -> "DECIMAL"
        ClickHouseTypeFamily.BOOLEAN -> "BOOLEAN"
        // Strings, enums and dates; arrays and unsupported columns never get here.
        else -> "STRING"
    }
}
