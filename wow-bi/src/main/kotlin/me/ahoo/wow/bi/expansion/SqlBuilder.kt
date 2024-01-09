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

package me.ahoo.wow.bi.expansion

import me.ahoo.wow.bi.expansion.column.Column
import me.ahoo.wow.bi.expansion.column.MetadataColumn

class SqlBuilder(
    val targetTableName: String,
    val sourceTableName: String,
    private val parent: SqlBuilder? = null
) {
    private val columns = mutableListOf<Column>()
    private val allColumns: List<Column>
        get() {
            val parentColumns = parent?.allColumns ?: return columns
            return parentColumns + columns
        }

    fun List<Column>.withMetadataColumns(): List<Column> {
        return this + listOf(
            MetadataColumn.ID_COLUMN,
            MetadataColumn.AGGREGATE_ID_COLUMN,
            MetadataColumn.TENANT_ID_COLUMN,
            MetadataColumn.COMMAND_ID_COLUMN,
            MetadataColumn.REQUEST_ID_COLUMN,
            MetadataColumn.VERSION_COLUMN,
            MetadataColumn.FIRST_OPERATOR_COLUMN,
            MetadataColumn.FIRST_EVENT_TIME_COLUMN,
            MetadataColumn.CREATE_TIME_COLUMN,
            MetadataColumn.DELETED_COLUMN
        )
    }

    fun append(column: Column) {
        columns.add(column)
    }

    private fun withSql(): String {
        return allColumns.filter {
            !it.isSimple
        }.joinToString(",\n") {
            it.expression
        }
    }

    private fun selectSql(): String {
        return allColumns.withMetadataColumns().filter {
            it.isSimple
        }.joinToString(",\n") {
            it.expression
        }
    }

    fun build(): String {
        return buildString {
            appendLine("CREATE VIEW IF NOT EXISTS bi_db.$targetTableName ON CLUSTER '{cluster}' AS")
            val withSql = withSql()
            if (withSql.isNotBlank()) {
                appendLine("WITH")
                append(withSql)
            }
            appendLine()
            appendLine("SELECT")
            append(selectSql())
            appendLine()
            appendLine("FROM bi_db.$sourceTableName;")
        }
    }

    fun copy(targetTableName: String): SqlBuilder {
        return SqlBuilder(
            targetTableName = targetTableName,
            sourceTableName = sourceTableName,
            parent = this
        )
    }
}
