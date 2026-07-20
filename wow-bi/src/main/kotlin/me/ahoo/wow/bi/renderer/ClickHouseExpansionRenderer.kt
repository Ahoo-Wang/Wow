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

import me.ahoo.wow.bi.BiObjectKey
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.ExpectedBiQuery
import me.ahoo.wow.bi.expansion.plan.CollectionCursorPlan
import me.ahoo.wow.bi.expansion.plan.ColumnExtraction
import me.ahoo.wow.bi.expansion.plan.ColumnPlacement
import me.ahoo.wow.bi.expansion.plan.ColumnPlan
import me.ahoo.wow.bi.expansion.plan.ColumnReference
import me.ahoo.wow.bi.expansion.plan.ExpansionViewPlan
import me.ahoo.wow.bi.expansion.plan.JsonPointerSegment
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan

/** Keeps emitted DDL and drift-manifest SQL on the same SELECT builder. */
@Suppress("TooManyFunctions")
internal class ClickHouseExpansionRenderer(private val context: ClickHouseRenderContext) {
    fun expectedQueries(plan: StateExpansionPlan): Map<BiObjectKey, ExpectedBiQuery> = with(context) {
        plan.views.associate { view ->
            BiObjectKey(options.database, view.targetTableName) to ExpectedBiQuery(renderSelect(view))
        }
    }

    fun render(plan: StateExpansionPlan, aggregate: String): List<String> =
        immutableStatements(plan.views.map { view -> renderView(view, aggregate) })

    private fun renderView(view: ExpansionViewPlan, aggregate: String): String = with(context) {
        buildString {
            appendLine(
                "$viewCreateClause ${qualified(options.database, view.targetTableName)}${scopeClause()} AS ("
            )
            appendLine(renderSelect(view))
            append(") COMMENT ${metadataComment(BiObjectKind.VIEW, aggregate)};")
        }
    }

    private fun renderSelect(view: ExpansionViewPlan): String = with(context) {
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
        buildString {
            if (withSql.isNotBlank()) {
                appendLine("WITH")
                appendLine(withSql)
            }
            appendLine("SELECT")
            appendLine(selectSql)
            append(
                "FROM ${qualified(options.database, view.sourceTableName)} AS " +
                    identifier(ClickHouseScriptRenderer.SOURCE_ALIAS)
            )
        }
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
        add(
            "${renderReference(ColumnReference.Input(ClickHouseScriptRenderer.STATE_COLUMN))} AS " +
                identifier(ClickHouseScriptRenderer.STATE_TARGET)
        )
        view.recovery.currentIndex?.let { currentIndex ->
            add(
                "toUInt64(${renderZeroBasedIndex(currentIndex)}) AS " +
                    identifier(ClickHouseScriptRenderer.INDEX_TARGET)
            )
        }
        add("${renderPointer(view.recovery.pointer)} AS ${identifier(ClickHouseScriptRenderer.PATH_TARGET)}")
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
}
