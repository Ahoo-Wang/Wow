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

package me.ahoo.wow.analytics.catalog.domain.dataset

import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetField
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKind
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetScope
import me.ahoo.wow.analytics.catalog.domain.source.SourceState

/** What compiling a dataset's definition gives: its [Compiled] artifact, or why it does not compile. */
sealed interface DatasetCompilation {
    data class Compiled(val artifact: DatasetArtifact) : DatasetCompilation

    data class Invalid(val problems: List<String>) : DatasetCompilation
}

/**
 * Compiles a checked definition ([BoundDataset]) into one ClickHouse view of one version (decision D259 7.2b-2), a
 * pure function: the view `ds_<context>_<name>_v<version>` of [database] (`wow_datasets` by default), the DDL that
 * creates it, and what its queries are served by ([DatasetArtifact]): the dataset's scope, its fields and their
 * protection, its lineage.
 *
 * The view reads each source as its rows are now: a source whose engine merges rows (`ReplacingMergeTree` and the
 * others that collapse, aggregate or sum) through `FINAL`; a left join keeps empty right sides `NULL`
 * (`join_use_nulls`); a value inside a JSON text column through `JSONExtractString`, inside a `JSON` column through
 * its subcolumn. Every name is quoted and every text literal escaped, so nothing of a definition is spliced in.
 */
object DatasetCompiler {
    /** The database the views are created in unless the service names another. */
    const val DEFAULT_DATABASE: String = "wow_datasets"

    private val MERGING = Regex("(Replacing|Collapsing|VersionedCollapsing|Aggregating|Summing|Coalescing)MergeTree")
    private val UNSAFE = Regex("[^a-z0-9_]")

    /** The name of [version]'s view of the dataset [name] of [context]: `ds_<context>_<name>_v<version>`. */
    fun viewOf(context: String, name: String, version: Int): String =
        "ds_${context}_${name}_v$version".lowercase().replace(UNSAFE, "_")

    /**
     * [version] of the dataset [name] of [context] from [definition], checked against the catalog's [sources] as they
     * are now ([DatasetBinder]), its view in [database]; with [approvedOnly], only of sources an approver approved.
     */
    @Suppress("LongParameterList") // The definition, what it reads, and where its view goes.
    fun compile(
        definition: DatasetDefinition,
        sources: Map<String, SourceState?>,
        context: String,
        name: String,
        version: Int,
        database: String = DEFAULT_DATABASE,
        title: String? = null,
        approvedOnly: Boolean = false,
    ): DatasetCompilation = when (val bound = DatasetBinder.bind(definition, sources, approvedOnly)) {
        is DatasetBinder.Result.Invalid -> DatasetCompilation.Invalid(bound.problems)
        is DatasetBinder.Result.Bound ->
            DatasetCompilation.Compiled(compile(bound.dataset, context, name, version, database, title))
    }

    @Suppress("LongParameterList")
    internal fun compile(
        dataset: BoundDataset,
        context: String,
        name: String,
        version: Int,
        database: String = DEFAULT_DATABASE,
        title: String? = null,
    ): DatasetArtifact {
        require(version > 0) { "A version is 1 or more." }
        val view = viewOf(context, name, version)
        val scope = scopeOf(dataset)
        val select = buildString {
            append("SELECT\n")
            val columns = scope.columns.map { (column, ref) -> "${expression(ref, dataset)} AS ${quote(column)}" } +
                dataset.columns.map { "${column(it, dataset)} AS ${quote(it.name)}" }
            append(columns.joinToString(",\n") { "    $it" })
            append("\nFROM ").append(source(dataset.subject))
            dataset.joins.forEach { join ->
                append('\n').append(if (join.kind == DatasetJoinKind.LEFT) "LEFT JOIN " else "INNER JOIN ")
                append(source(join.source)).append(" ON ")
                append(join.on.joinToString(" AND ") { (left, right) -> "${ref(left)} = ${ref(right)}" })
            }
            if (dataset.filters.isNotEmpty()) {
                append("\nWHERE ").append(dataset.filters.joinToString(" AND ") { "(${expression(it, dataset)})" })
            }
            if (dataset.joins.any { it.kind == DatasetJoinKind.LEFT }) {
                append("\nSETTINGS join_use_nulls = 1")
            }
        }
        return DatasetArtifact(
            context = context,
            name = name,
            version = version,
            database = database,
            view = view,
            ddl = "CREATE OR REPLACE VIEW ${quote(database)}.${quote(view)} AS\n$select",
            title = title,
            scope = scope.value,
            fields = dataset.columns.map { column ->
                DatasetField(
                    name = column.name,
                    title = column.title,
                    sensitivity = column.sensitivity,
                    inputs = column.expression.refs().map { inputOf(it, dataset) }.distinct(),
                    computed = column.computed,
                )
            },
            identity = dataset.identity,
            eventTime = dataset.eventTime,
            sources = dataset.sources.map { it.id },
            approvals = dataset.approvals,
        )
    }

    private class Scope(val value: DatasetScope, val columns: List<Pair<String, DatasetExpression.Ref>>)

    /** The subject's scope: its tenant, owner and space columns, as `tenant_id`, `owner_id`, `space_id`. */
    private fun scopeOf(dataset: BoundDataset): Scope {
        val subject = dataset.subject
        val state = subject.state
        val columns = listOfNotNull(
            state.tenantColumn.takeUnless { state.platform }?.let { DatasetBinder.TENANT to it },
            state.ownerColumn?.let { DatasetBinder.OWNER to it },
            state.spaceColumn?.let { DatasetBinder.SPACE to it },
        ).map { (name, column) -> name to DatasetExpression.Ref(subject.alias, column) }
        val names = columns.map { it.first }.toSet()
        return Scope(
            DatasetScope(
                tenant = DatasetBinder.TENANT.takeIf { it in names },
                owner = DatasetBinder.OWNER.takeIf { it in names },
                space = DatasetBinder.SPACE.takeIf { it in names },
                platform = dataset.platform,
            ),
            columns,
        )
    }

    /** [ref]'s value as the lineage names it: `<source id>/<column>[.<path>]`. */
    private fun inputOf(ref: DatasetExpression.Ref, dataset: BoundDataset): String {
        val id = dataset.sources.first { it.alias == ref.alias }.id
        return "$id/${(listOf(ref.column) + ref.path).joinToString(".")}"
    }

    private fun source(source: BoundSource): String {
        val table = "${quote(source.state.database)}.${quote(source.state.table)}"
        return if (MERGING.containsMatchIn(source.state.tableEngine)) {
            "(SELECT * FROM $table FINAL) AS ${quote(source.alias)}"
        } else {
            "$table AS ${quote(source.alias)}"
        }
    }

    private fun ref(ref: DatasetExpression.Ref): String = "${quote(ref.alias)}.${quote(ref.column)}"

    @Suppress("CyclomaticComplexMethod") // One branch per kind of expression.
    private fun expression(expression: DatasetExpression, dataset: BoundDataset): String = when (expression) {
        is DatasetExpression.Ref -> if (expression.path.isEmpty()) {
            ref(expression)
        } else {
            val type = dataset.sources.first { it.alias == expression.alias }.state.columns
                .first { it.name == expression.column }.type
            if (JSON_TYPE.containsMatchIn(type)) {
                (listOf(ref(expression)) + expression.path.map(::quote)).joinToString(".")
            } else {
                "JSONExtractString(${ref(expression)}, ${expression.path.joinToString(", ") { literal(it) }})"
            }
        }

        is DatasetExpression.Literal -> when (expression.kind) {
            DatasetExpression.Literal.Kind.STRING -> literal(expression.text)
            DatasetExpression.Literal.Kind.NULL -> "NULL"
            else -> expression.text
        }

        is DatasetExpression.Call -> call(expression, dataset)

        is DatasetExpression.Unary -> if (expression.operator == "NOT") {
            "NOT (${expression(expression.operand, dataset)})"
        } else {
            "-(${expression(expression.operand, dataset)})"
        }

        is DatasetExpression.Binary -> binary(expression, dataset)

        is DatasetExpression.In -> {
            val operand = expression(expression.operand, dataset)
            val values = expression.values.joinToString(", ") { expression(it, dataset) }
            if (expression.negated) {
                keepingNull(expression.operand, "$operand NOT IN ($values)", dataset)
            } else {
                "$operand IN ($values)"
            }
        }

        is DatasetExpression.Between -> "(${expression(expression.operand, dataset)} BETWEEN " +
            "${expression(expression.lower, dataset)} AND ${expression(expression.upper, dataset)})"

        is DatasetExpression.IsNull ->
            "${expression(expression.operand, dataset)} ${if (expression.negated) "IS NOT NULL" else "IS NULL"}"
    }

    private val JSON_TYPE = Regex("^(Nullable\\()?(JSON|Object\\()")

    /** The text types: `String` and `FixedString`, maybe `Nullable` or `LowCardinality`. */
    private val TEXT_TYPE = Regex("^(LowCardinality\\()?(Nullable\\()?(String|FixedString\\(\\d+\\))\\)*$")

    /**
     * [column]'s value in the view: a sensitive value is masked as text, so one that is not text already (a number, a
     * date, a value computed or inside a `JSON` column) is shown by its text, `toString(...)`.
     */
    private fun column(column: BoundColumn, dataset: BoundDataset): String {
        val sql = expression(column.expression, dataset)
        return if (column.sensitivity == null || text(column.expression, dataset)) sql else "toString($sql)"
    }

    /** Whether [expression] is text already: a column of a text type, or a value read out of JSON text. */
    private fun text(expression: DatasetExpression, dataset: BoundDataset): Boolean {
        val ref = expression as? DatasetExpression.Ref ?: return false
        val type = dataset.sources.first { it.alias == ref.alias }.state.columns.first { it.name == ref.column }.type
        return if (ref.path.isEmpty()) TEXT_TYPE.matches(type) else !JSON_TYPE.containsMatchIn(type)
    }

    /**
     * A comparison as the query protocol means it (`FilterExpression`): `= null` is `IS NULL`, `!= null` is
     * `IS NOT NULL`, and `!=` keeps the rows whose value is null.
     */
    private fun binary(expression: DatasetExpression.Binary, dataset: BoundDataset): String {
        val operator = expression.operator
        val nothing = (expression.right as? DatasetExpression.Literal)?.kind == DatasetExpression.Literal.Kind.NULL
        if (nothing && operator == "=") return "${expression(expression.left, dataset)} IS NULL"
        if (nothing && operator == "!=") return "${expression(expression.left, dataset)} IS NOT NULL"
        val sql = "(${expression(expression.left, dataset)} $operator ${expression(expression.right, dataset)})"
        return if (operator == "!=") keepingNull(expression.left, sql, dataset) else sql
    }

    /**
     * [predicate] of [operand], also true where [operand] is null, as `NE` and `NOT_IN` are: a nullable column, a value
     * inside a `JSON` column, or any value of a source a left join brings in (`join_use_nulls`).
     */
    private fun keepingNull(operand: DatasetExpression, predicate: String, dataset: BoundDataset): String {
        val ref = operand as? DatasetExpression.Ref ?: return predicate
        val type = dataset.sources.first { it.alias == ref.alias }.state.columns.first { it.name == ref.column }.type
        val joinedLeft = dataset.joins.any { it.kind == DatasetJoinKind.LEFT && it.source.alias == ref.alias }
        val nullable = joinedLeft || type.startsWith("Nullable(") || ref.path.isNotEmpty() && JSON_TYPE.containsMatchIn(type)
        return if (nullable) "($predicate OR isNull(${expression(ref, dataset)}))" else predicate
    }

    /** A call; the query protocol's text tests as its ClickHouse compiler writes them. */
    private fun call(call: DatasetExpression.Call, dataset: BoundDataset): String {
        val arguments = call.arguments.map { expression(it, dataset) }
        val ignoreCase = call.function.endsWith(IGNORE_CASE)
        val folded = if (ignoreCase) arguments.map { "lowerUTF8($it)" } else arguments
        return when (call.function.removeSuffix(IGNORE_CASE)) {
            "contains" -> if (ignoreCase) {
                "(positionCaseInsensitiveUTF8(${arguments.joinToString(", ")}) > 0)"
            } else {
                "(position(${arguments.joinToString(", ")}) > 0)"
            }

            "startsWith" -> "startsWith(${folded.joinToString(", ")})"
            "endsWith" -> "endsWith(${folded.joinToString(", ")})"
            else -> "${call.function}(${arguments.joinToString(", ")})"
        }
    }

    private const val IGNORE_CASE = "IgnoreCase"

    /** [name] as a ClickHouse identifier, in backquotes. */
    internal fun quote(name: String): String = "`${name.replace("\\", "\\\\").replace("`", "\\`")}`"

    /** [text] as a ClickHouse string literal. */
    internal fun literal(text: String): String = "'${text.replace("\\", "\\\\").replace("'", "\\'")}'"
}
