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

import me.ahoo.wow.analytics.catalog.api.dataset.DatasetDefinition
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetJoinKind
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetReference
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetSensitivity
import me.ahoo.wow.analytics.catalog.api.source.FieldSensitivity
import me.ahoo.wow.analytics.catalog.api.source.SourceKind
import me.ahoo.wow.analytics.catalog.api.source.SourceStatus
import me.ahoo.wow.analytics.catalog.domain.source.SourceSensitivity
import me.ahoo.wow.analytics.catalog.domain.source.SourceState

/** A source of a definition, under its [alias]: the catalog's source [id] as it is now ([state]). */
internal data class BoundSource(val alias: String, val id: String, val state: SourceState)

/** A join of a definition: [source] brought in by [kind], each key pairing a column before it with one of its own. */
internal data class BoundJoin(
    val source: BoundSource,
    val kind: DatasetJoinKind,
    val on: List<Pair<DatasetExpression.Ref, DatasetExpression.Ref>>,
)

/**
 * A column of a definition: the [expression] it shows (a [DatasetExpression.Ref] unless [computed]), the
 * [sensitivity] its lineage, or the definition, gives it (null when it is not sensitive), and the aggregate its value
 * is the id of ([reference]).
 */
internal data class BoundColumn(
    val name: String,
    val expression: DatasetExpression,
    val computed: Boolean,
    val title: String?,
    val sensitivity: DatasetSensitivity?,
    val reference: DatasetReference? = null,
)

/**
 * A definition checked against the catalog: its [subject], [joins], [columns] and [filters], with every reference
 * resolved; [approvals] says why publishing it takes an approver (-2), empty when it does not.
 */
internal data class BoundDataset(
    val subject: BoundSource,
    val joins: List<BoundJoin>,
    val columns: List<BoundColumn>,
    val filters: List<DatasetExpression>,
    val identity: String?,
    val eventTime: String?,
    val approvals: List<String>,
) {
    /** Whether every tenant shares the dataset's rows: its subject is a platform dimension. */
    val platform: Boolean get() = subject.state.platform

    val sources: List<BoundSource> get() = listOf(subject) + joins.map { it.source }
}

/**
 * Checks a dataset's definition against the catalog's sources as they are now (-2),
 * a pure function of both: the sources are reviewed or adopted, on the table they describe and on one warehouse; each
 * source but the subject is joined once, after the sources its keys read, on the tenant unless it is an approved
 * platform dimension; the references name the sources' columns; the expressions parse and call only the allowed
 * functions; and each column's protection follows its lineage.
 */
internal object DatasetBinder {
    /** The names the dataset's scope columns take: the subject's tenant, owner and space. */
    const val TENANT: String = "tenant_id"
    const val OWNER: String = "owner_id"
    const val SPACE: String = "space_id"
    val SCOPE: Set<String> = setOf(TENANT, OWNER, SPACE)

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    sealed interface Result {
        data class Bound(val dataset: BoundDataset) : Result

        data class Invalid(val problems: List<String>) : Result
    }

    /** [definition] over [sources], each source it names by id as the catalog has it now, or null when it has none. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One check after another, each adding its problem.
    fun bind(definition: DatasetDefinition, sources: Map<String, SourceState?>, approvedOnly: Boolean = false): Result {
        val problems = mutableListOf<String>()
        val aliases = linkedMapOf<String, BoundSource>()
        definition.sources.forEach { source ->
            if (!NAME.matches(source.alias)) {
                problems += "The alias ${source.alias} is not a name: letters, digits and _."
            } else if (source.alias in aliases) {
                problems += "The alias ${source.alias} names one source only."
            } else {
                val state = sources[source.sourceId]
                val unusable = unusable(source.sourceId, state, approvedOnly)
                if (unusable != null) problems += unusable else aliases[source.alias] = BoundSource(source.alias, source.sourceId, state!!)
            }
        }
        if (definition.sources.isEmpty()) problems += "A dataset reads one source at least."
        val warehouses = aliases.values.map { it.state.connection to it.state.engine }.distinct()
        if (warehouses.size > 1) problems += "A dataset reads one warehouse only, not $warehouses."
        val subject = aliases[definition.subject]
        if (subject == null && definition.sources.none { it.alias == definition.subject }) {
            problems += "The subject ${definition.subject} is not an alias of the sources."
            // Every join and reference is read from the subject on: without it, nothing else can be checked.
            return Result.Invalid(problems)
        }

        fun parse(text: String, what: String): DatasetExpression? = try {
            DatasetExpression.parse(text)
        } catch (error: DatasetExpressionException) {
            problems += "$what: ${error.message}"
            null
        }

        /** [expression] when each reference it reads names a column of a source in [scope], else null. */
        fun resolve(expression: DatasetExpression, scope: Set<String>, what: String): DatasetExpression? {
            var resolved = true
            expression.refs().forEach { ref ->
                val source = aliases[ref.alias]
                when {
                    source == null && definition.sources.none { it.alias == ref.alias } -> {
                        problems += "$what reads $ref: ${ref.alias} is not an alias of the sources."
                        resolved = false
                    }

                    source == null -> resolved = false
                    ref.alias !in scope -> {
                        problems += "$what reads $ref before ${ref.alias} is joined."
                        resolved = false
                    }

                    else -> DatasetLineage.problem(source.state, ref)?.let {
                        problems += "$what reads $ref: $it"
                        resolved = false
                    }
                }
            }
            return expression.takeIf { resolved }
        }

        fun ref(text: String, scope: Set<String>, what: String): DatasetExpression.Ref? {
            val expression = parse(text, what) ?: return null
            if (expression !is DatasetExpression.Ref) {
                problems += "$what is $text: a reference <alias>.<column>."
                return null
            }
            return resolve(expression, scope, what) as DatasetExpression.Ref?
        }

        // The joins, in order: each brings one source in, keyed on what is joined before it.
        val joined = linkedSetOf(definition.subject)
        val joins = mutableListOf<BoundJoin>()
        definition.joins.forEach { join ->
            val what = "The join of ${join.alias}"
            when {
                join.alias == definition.subject -> problems += "$what: the subject is not joined."
                join.alias in joined -> problems += "$what: a source is joined once."
                definition.sources.none { it.alias == join.alias } ->
                    problems += "$what: ${join.alias} is not an alias of the sources."
            }
            if (join.on.isEmpty()) problems += "$what has no key."
            val keys = join.on.mapNotNull { key ->
                val left = ref(key.left, joined, "$what")
                val right = ref(key.right, setOf(join.alias), "$what")
                val path = listOfNotNull(left, right).any { it.path.isNotEmpty() }
                if (path) {
                    problems += "$what keys on ${key.left} = ${key.right}: a key is a whole column."
                    null
                } else if (left != null && right != null) {
                    left to right
                } else {
                    null
                }
            }
            val source = aliases[join.alias]
            if (source != null && keys.size == join.on.size && subject != null) {
                tenantProblem(source, keys, aliases, subject)?.let { problems += "$what $it" }
                joins += BoundJoin(source, join.kind, keys)
            }
            joined += join.alias
        }
        definition.sources.map { it.alias }.filter { it !in joined }.distinct().forEach {
            problems += "The source $it is never joined."
        }

        // The columns: each from a source's value or computed from them, its protection from its lineage.
        val approvals = mutableListOf<String>()
        val columns = mutableListOf<BoundColumn>()
        val names = HashSet<String>()
        if (definition.columns.isEmpty()) problems += "A dataset shows one column at least."
        definition.columns.forEach { column ->
            val what = "The column ${column.name}"
            when {
                !NAME.matches(column.name) -> problems += "$what is not a name: letters, digits and _."
                column.name in SCOPE -> problems += "$what is the subject's scope column, which the dataset has already."
                !names.add(column.name) -> problems += "$what is declared once only."
            }
            column.sensitivity?.let {
                if (it.keepPrefix < 0 || it.keepSuffix < 0) problems += "$what keeps no negative number of characters."
            }
            val expression = when {
                (column.from == null) == (column.expression == null) -> {
                    problems += "$what is either from a source's value or an expression."
                    null
                }

                column.from != null -> ref(column.from!!, joined, what)
                else -> parse(column.expression!!, what)?.let { resolve(it, joined, what) }
            } ?: return@forEach
            val computed = column.from == null
            val lineage = DatasetLineage.of(expression, aliases, keepMask = !computed)
            val sensitive = expression.refs().filter { DatasetLineage.of(it, aliases, keepMask = false) != null }
            if (computed && sensitive.isNotEmpty()) {
                approvals += "$what computes from the sensitive values ${sensitive.distinct()}."
            }
            val given = column.sensitivity
            if (given != null && SourceSensitivity.rank(given.level) < SourceSensitivity.rank(lineage?.level)) {
                approvals += "$what is given ${given.level ?: "no protection"}, lower than its lineage's ${lineage?.level}."
            }
            columns += BoundColumn(
                name = column.name,
                expression = expression,
                computed = computed,
                title = column.title,
                sensitivity = given?.takeUnless { it.level == null } ?: lineage.takeIf { given == null },
                reference = (expression as? DatasetExpression.Ref)?.let {
                    DatasetLineage.referenceOf(aliases.getValue(it.alias).state, it)
                },
            )
        }
        listOfNotNull(definition.identity, definition.eventTime).filter { it !in names }.forEach {
            problems += "$it is not a column of the dataset."
        }

        // The filters every row passes.
        val filters = definition.filters.mapIndexedNotNull { index, filter ->
            val what = "The filter ${index + 1}"
            parse(filter, what)?.let { resolve(it, joined, what) }
        }
        if (problems.isNotEmpty() || subject == null) return Result.Invalid(problems.distinct())
        return Result.Bound(
            BoundDataset(
                subject = subject,
                joins = joins,
                columns = columns,
                filters = filters,
                identity = definition.identity,
                eventTime = definition.eventTime,
                approvals = approvals,
            ),
        )
    }

    /**
     * Why the catalog's source [id] ([state]) is not one a dataset reads, or null when it is; with [approvedOnly], one
     * an approver has not approved is not either.
     */
    private fun unusable(id: String, state: SourceState?, approvedOnly: Boolean): String? = when {
        state == null || !state.exists -> "The catalog has no source $id."
        state.missing -> "The source $id's table is gone."
        state.status == SourceStatus.DRAFT -> "The source $id is a draft: it is reviewed first."
        approvedOnly && !state.approved -> "The source $id awaits an approver's approval."
        else -> null
    }

    /**
     * Why the join of [source] by [keys] does not keep each tenant's rows apart, or null when it does: one key pairs
     * the tenant columns of a source before it and of [source], unless [source] is a platform dimension; a dataset
     * whose [subject] is one joins platform dimensions only.
     */
    private fun tenantProblem(
        source: BoundSource,
        keys: List<Pair<DatasetExpression.Ref, DatasetExpression.Ref>>,
        aliases: Map<String, BoundSource>,
        subject: BoundSource,
    ): String? {
        if (source.state.platform) return null
        if (subject.state.platform) {
            return "brings a tenant's rows into a platform dimension's: its subject's rows are every tenant's."
        }
        val tenant = keys.any { (left, right) ->
            val before = aliases.getValue(left.alias).state
            !before.platform && left.column == before.tenantColumn && right.column == source.state.tenantColumn
        }
        return if (tenant) {
            null
        } else {
            "keys on no tenant: one key pairs the tenant columns, unless ${source.alias} is an approved platform dimension."
        }
    }
}

/** The protection a dataset's value inherits from the sources it reads. */
internal object DatasetLineage {
    /** The column of a Wow table that holds its aggregate's id (the Sink's `aggregate_id`). */
    const val AGGREGATE_ID: String = "aggregate_id"

    /**
     * The aggregate the value [ref] reads is the id of, or null when it is no id: a value the [source] declares a
     * reference to a known aggregate, or a Wow table's `aggregate_id`, the id of the aggregate the table is of.
     */
    fun referenceOf(source: SourceState, ref: DatasetExpression.Ref): DatasetReference? {
        val path = (listOf(ref.column) + ref.path).joinToString(".")
        source.references.firstOrNull { it.path == path }?.let { return DatasetReference(it.context, it.aggregate) }
        val enrichment = source.enrichment.takeIf { source.kind == SourceKind.WOW } ?: return null
        return DatasetReference(enrichment.context, enrichment.aggregate).takeIf { path == AGGREGATE_ID }
    }

    /** The types a value with a path inside is read from: JSON text, or a `JSON` column. */
    private val JSON = Regex("(Nullable\\()?(LowCardinality\\()?(Nullable\\()?(String|JSON.*|Object\\(.*)\\)*")

    /** Why [ref] names no value of [source], or null when it does. */
    fun problem(source: SourceState, ref: DatasetExpression.Ref): String? {
        val column = source.columns.firstOrNull { it.name == ref.column }
            ?: return "${ref.column} is not a column of ${source.database}.${source.table}."
        if (ref.path.isNotEmpty() && !JSON.matches(column.type)) {
            return "${ref.column} is ${column.type}, not a JSON value with a path inside."
        }
        return null
    }

    /**
     * The protection of [expression]'s value, or null when none of what it reads is sensitive: the highest level of
     * its references; with the mask of the one value it reads when [keepMask] and it reads one value marked at that
     * level, and a full mask otherwise.
     */
    fun of(
        expression: DatasetExpression,
        aliases: Map<String, BoundSource>,
        keepMask: Boolean,
    ): DatasetSensitivity? {
        val marks = expression.refs().flatMap { marks(aliases.getValue(it.alias).state, it) }
        val top = marks.maxByOrNull { SourceSensitivity.rank(it.second.level) } ?: return null
        val (whole, mark) = top
        return if (keepMask && whole && mark.strategy == null) {
            DatasetSensitivity(mark.level, mark.keepPrefix, mark.keepSuffix)
        } else {
            DatasetSensitivity(mark.level)
        }
    }

    /**
     * The sensitive marks of [source] on the value [ref] reads: a mark of that value or of one that holds it
     * (`true`: its mask applies to the whole value), or of a value inside it (`false`). A JSON column's own mark is
     * the highest of the values inside it once they are marked one by one (a Wow source's): a value inside the
     * column then takes only the marks of its own path.
     */
    private fun marks(source: SourceState, ref: DatasetExpression.Ref): List<Pair<Boolean, FieldSensitivity>> {
        val path = (listOf(ref.column) + ref.path).joinToString(".")
        val marked = source.sensitivity.filter { it.level != null }
        val inside = ref.path.isNotEmpty() && marked.any { it.path.startsWith("${ref.column}.") }
        return marked.mapNotNull { mark ->
            when {
                inside && mark.path == ref.column -> null
                mark.path == path || path.startsWith("${mark.path}.") -> true to mark
                mark.path.startsWith("$path.") -> false to mark
                else -> null
            }
        }
    }
}
