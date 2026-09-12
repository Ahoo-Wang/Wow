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

package me.ahoo.wow.elasticsearch.query.aggregation

import co.elastic.clients.elasticsearch._types.Script
import co.elastic.clients.elasticsearch._types.ScriptLanguage
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource
import co.elastic.clients.elasticsearch._types.mapping.RuntimeField
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.json.JsonData
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortCompiler.toSortOrder
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.distinctCountCapability
import me.ahoo.wow.query.schema.physicalField
import me.ahoo.wow.query.schema.requireScalarMetricFilterFields
import java.time.Instant
import java.util.concurrent.TimeUnit

internal data class ElasticsearchAggregationPlan(
    val rootQuery: Query,
    val elements: List<ElasticsearchAggregationElement>,
    val groupSources: List<NamedValue<CompositeAggregationSource>>,
    val metrics: List<ElasticsearchAggregationMetric>,
    val runtimeMappings: Map<String, RuntimeField>,
    val effectiveSort: List<Sort>,
    val limit: Int,
    val metricSorted: Boolean,
    val having: HavingExpression? = null,
)

internal data class ElasticsearchAggregationElement(
    val path: String,
    val filter: Query,
)

internal sealed interface ElasticsearchAggregationMetric {
    val alias: String
    val filter: Query?

    data class Count(
        override val alias: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Numeric(
        override val alias: String,
        val function: AggregationFunction,
        val field: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Any(
        override val alias: String,
        val field: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class DistinctCount(
        override val alias: String,
        val field: String,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Percentile(
        override val alias: String,
        val field: String,
        val percentile: Double,
        override val filter: Query? = null,
    ) : ElasticsearchAggregationMetric

    data class Derived(
        override val alias: String,
        val bucketsPath: Map<String, String>,
        val script: Script,
    ) : ElasticsearchAggregationMetric {
        override val filter: Query? = null
    }
}

internal val ElasticsearchAggregationMetric.valueCountAlias: String
    get() = "__wow_value_count_$alias"

internal class ElasticsearchAggregationCompiler(
    private val filterCompiler: AbstractElasticsearchFilterCompiler,
) {
    fun compile(query: AggregationQuery, schema: QueryModelSchema): ElasticsearchAggregationPlan = compile(
        query,
        schema,
        Instant.now()
    )

    internal fun compile(query: AggregationQuery, schema: QueryModelSchema, now: Instant): ElasticsearchAggregationPlan {
        val rootQuery = filterCompiler.compile(query.filter, schema, now)
        val elements = mutableListOf<ElasticsearchAggregationElement>()
        var logicalParent: QueryField? = null
        var physicalParent: QueryField? = null
        query.elements.forEach { element ->
            val previousLogicalParent = logicalParent
            logicalParent = previousLogicalParent?.append(element.path) ?: element.path
            val nestedPath = element.path.resolve(
                previousLogicalParent,
                physicalParent,
                schema,
                QueryCapability.ELEMENT_SCOPE,
            )
            physicalParent = QueryField(nestedPath)
            elements += ElasticsearchAggregationElement(
                path = nestedPath,
                filter = filterCompiler.compileScoped(
                    element.filter,
                    schema,
                    logicalParent,
                    physicalParent,
                    now,
                ),
            )
        }

        val runtimeMappings = linkedMapOf<String, RuntimeField>()
        val effectiveSort = query.effectiveSort()
        val groups = query.groupBy.withIndex().associateBy { it.value.alias }
        val groupSources = effectiveSort.mapNotNull { sort ->
            groups[sort.field.path]?.let { indexed ->
                indexed.value.toSource(logicalParent, physicalParent, sort, indexed.index, schema, runtimeMappings)
            }
        }
        val metricPlans = compileMetrics(query, logicalParent, physicalParent, schema, runtimeMappings, now)
        val metricAliases = query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        return ElasticsearchAggregationPlan(
            rootQuery = rootQuery,
            elements = elements,
            groupSources = groupSources,
            metrics = metricPlans,
            runtimeMappings = runtimeMappings,
            effectiveSort = effectiveSort,
            limit = query.limit,
            metricSorted = effectiveSort.any { it.field.path in metricAliases },
            having = query.having,
        )
    }

    /**
     * Compiles the declared metrics in order. Declaration order (list iteration order) lets a
     * [AggregationMetric.Derived] metric resolve the plans of metrics declared before it;
     * referenced aliases keep one stable `vN`/`cN` param index across the whole compile.
     */
    private fun compileMetrics(
        query: AggregationQuery,
        logicalParent: QueryField?,
        physicalParent: QueryField?,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        now: Instant,
    ): List<ElasticsearchAggregationMetric> {
        val metricPlans = mutableListOf<ElasticsearchAggregationMetric>()
        val priorByAlias = linkedMapOf<String, ElasticsearchAggregationMetric>()
        val derivedRefIndexes = linkedMapOf<String, Int>()
        query.metrics.forEachIndexed { index, metric ->
            val plan = metric.toPlan(
                logicalParent,
                physicalParent,
                index,
                schema,
                runtimeMappings,
                now,
                priorByAlias,
                derivedRefIndexes,
            )
            metricPlans += plan
            priorByAlias[metric.alias] = plan
        }
        return metricPlans
    }

    private fun AggregationGroup.toSource(
        parent: QueryField?,
        physicalParent: QueryField?,
        sort: Sort,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): NamedValue<CompositeAggregationSource> {
        val source = when (this) {
            is AggregationGroup.Terms -> {
                val declaredMissingKey = missingKey
                if (declaredMissingKey == null) {
                    CompositeAggregationSource.of {
                        it.terms { terms ->
                            terms.field(field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS))
                                .order(sort.direction.toSortOrder())
                        }
                    }
                } else {
                    val runtimeFieldName = "__wow_missing_terms_$index"
                    runtimeMappings[runtimeFieldName] = missingKeyRuntimeField(
                        field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS),
                        declaredMissingKey,
                    )
                    CompositeAggregationSource.of {
                        it.terms { terms ->
                            terms.field(runtimeFieldName).order(sort.direction.toSortOrder())
                        }
                    }
                }
            }

            is AggregationGroup.Histogram -> CompositeAggregationSource.of {
                it.histogram { histogram ->
                    histogram.field(field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC))
                        .interval(interval)
                        .order(sort.direction.toSortOrder())
                }
            }

            is AggregationGroup.DateHistogram -> CompositeAggregationSource.of {
                it.dateHistogram { dateHistogram ->
                    dateHistogram.field(dateField(parent, physicalParent, index, schema, runtimeMappings))
                    if (unit == AggregationDateUnit.SECOND) {
                        dateHistogram.fixedInterval { interval -> interval.time("1s") }
                    } else {
                        dateHistogram.calendarInterval { interval -> interval.time(unit.name.lowercase()) }
                    }
                    dateHistogram.timeZone(timeZone).order(sort.direction.toSortOrder())
                }
            }
        }
        return NamedValue.of(alias, source)
    }

    private fun AggregationGroup.DateHistogram.dateField(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): String {
        val logicalField = parent?.append(field) ?: field
        val capability = QueryCapability.AGGREGATE_TEMPORAL
        val fieldSchema = checkNotNull(schema.field(logicalField))
        val physicalPath = field.resolve(parent, physicalParent, schema, capability)
        return when (val semanticType = fieldSchema.value.temporalSemantic()) {
            Temporal.Date -> physicalPath
            is Temporal.Epoch -> "__wow_date_histogram_$index".also { runtimeFieldName ->
                runtimeMappings[runtimeFieldName] = epochDateRuntimeField(physicalPath, semanticType.timeUnit)
            }
            else -> throw QuerySchemaValidationException(
                "Query field [$logicalField] does not have a supported temporal semantic type.",
            )
        }
    }

    private fun epochDateRuntimeField(physicalPath: String, timeUnit: TimeUnit): RuntimeField {
        val (multiplier, divisor) = timeUnit.epochFactors
        val params = mapOf(
            "field" to JsonData.of(physicalPath),
            "multiplier" to JsonData.of(multiplier),
            "divisor" to JsonData.of(divisor),
        )
        val source = """
            String field = params.field;
            if (doc.containsKey(field) && doc[field].size() == 1) {
                def raw = doc[field].value;
                if (raw instanceof Number) {
                    boolean floating = raw instanceof Double || raw instanceof Float;
                    double numeric = ((Number) raw).doubleValue();
                    if (
                        Double.isFinite(numeric) &&
                        (!floating ||
                            (numeric >= -9.223372036854776E18 && numeric < 9.223372036854776E18))
                    ) {
                        long epoch = ((Number) raw).longValue();
                        if (!floating || numeric == (double) epoch) {
                            long divisor = ((Number) params.divisor).longValue();
                            long millis = epoch / divisor;
                            if (epoch < 0L && epoch % divisor != 0L) {
                                millis -= 1L;
                            }
                            long multiplier = ((Number) params.multiplier).longValue();
                            if (
                                millis <= Long.MAX_VALUE / multiplier &&
                                millis >= Long.MIN_VALUE / multiplier
                            ) {
                                emit(millis * multiplier);
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        return RuntimeField.of { runtime ->
            runtime.type(RuntimeFieldType.Date)
                .script(
                    Script.of { script ->
                        script.lang(ScriptLanguage.Painless)
                            .source { it.scriptString(source) }
                            .params(params)
                    },
                )
        }
    }

    /**
     * Single-valued passthrough with a declared sentinel: the sentinel stays a plain string key so
     * composite ordering matches MongoDB's `$ifNull` lexicographic position (composite
     * `missing_bucket` orders its null key first, which would diverge).
     */
    private fun missingKeyRuntimeField(physicalPath: String, missingKey: String): RuntimeField {
        val params = mapOf(
            "field" to JsonData.of(physicalPath),
            "missing" to JsonData.of(missingKey),
        )
        val source = """
            String field = params.field;
            if (doc.containsKey(field) && doc[field].size() == 1) {
                def raw = doc[field].value;
                if (raw != null) {
                    return raw.toString();
                }
            }
            return params.missing;
        """.trimIndent()
        return RuntimeField.of { runtime ->
            runtime.type(RuntimeFieldType.Keyword)
                .script(
                    Script.of { script ->
                        script.lang(ScriptLanguage.Painless)
                            .source { it.scriptString(source) }
                            .params(params)
                    },
                )
        }
    }

    private fun AggregationMetric.toPlan(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        now: Instant,
        prior: Map<String, ElasticsearchAggregationMetric>,
        derivedRefIndexes: MutableMap<String, Int>,
    ): ElasticsearchAggregationMetric {
        val filter = metricFilter(parent, physicalParent, schema, now)
        return when (this) {
            is AggregationMetric.Count -> ElasticsearchAggregationMetric.Count(alias, filter)
            is AggregationMetric.Any -> ElasticsearchAggregationMetric.Any(
                alias,
                field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS),
                filter,
            )
            is AggregationMetric.Numeric -> {
                val metricExpression = expression
                val scalarField = (metricExpression as? AggregationExpression.Field)?.field?.takeIf { field ->
                    val logicalField = parent?.append(field) ?: field
                    schema.field(logicalField)?.value?.cardinality == QueryCardinality.SINGLE
                }
                val metricField = if (scalarField != null) {
                    scalarField.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
                } else {
                    "__wow_expression_$index".also { runtimeFieldName ->
                        runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
                            parent,
                            physicalParent,
                            schema,
                        ).compile(metricExpression)
                    }
                }
                ElasticsearchAggregationMetric.Numeric(alias, function, metricField, filter)
            }

            is AggregationMetric.DistinctCount -> toDistinctCountPlan(
                parent,
                physicalParent,
                index,
                schema,
                runtimeMappings,
                filter,
            )

            is AggregationMetric.Percentile -> toPercentilePlan(
                parent,
                physicalParent,
                index,
                schema,
                runtimeMappings,
                filter,
            )

            is AggregationMetric.Derived -> toDerivedPlan(expression, prior, derivedRefIndexes)
        }
    }

    /**
     * Compiles the record-level filter of this metric against its enclosing scope,
     * or returns `null` for [MatchAllFilter] so unfiltered metrics keep their unwrapped aggregations.
     */
    private fun AggregationMetric.metricFilter(
        parent: QueryField?,
        physicalParent: QueryField?,
        schema: QueryModelSchema,
        now: Instant,
    ): Query? {
        val filter = this.filter
        if (filter === MatchAllFilter) {
            return null
        }
        filter.requireScalarMetricFilterFields(parent, schema)
        if (parent == null || physicalParent == null) {
            return filterCompiler.compile(filter, schema, now)
        }
        return filterCompiler.compileScoped(filter, schema, parent, physicalParent, now)
    }

    private fun AggregationMetric.DistinctCount.toDistinctCountPlan(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        filter: Query?,
    ): ElasticsearchAggregationMetric.DistinctCount {
        val metricField = (expression as? AggregationExpression.Field)?.field?.let { field ->
            field.resolve(parent, physicalParent, schema, schema.distinctCountCapability(field, parent))
        } ?: "__wow_expression_$index".also { runtimeFieldName ->
            runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
                parent,
                physicalParent,
                schema,
            ).compile(expression)
        }
        return ElasticsearchAggregationMetric.DistinctCount(alias, metricField, filter)
    }

    private fun AggregationMetric.Percentile.toPercentilePlan(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        filter: Query?,
    ): ElasticsearchAggregationMetric.Percentile {
        val metricExpression = expression
        val scalarField = (metricExpression as? AggregationExpression.Field)?.field?.takeIf { field ->
            val logicalField = parent?.append(field) ?: field
            schema.field(logicalField)?.value?.cardinality == QueryCardinality.SINGLE
        }
        val metricField = if (scalarField != null) {
            scalarField.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
        } else {
            "__wow_expression_$index".also { runtimeFieldName ->
                runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
                    parent,
                    physicalParent,
                    schema,
                ).compile(metricExpression)
            }
        }
        return ElasticsearchAggregationMetric.Percentile(alias, metricField, percentile, filter)
    }

    private fun AggregationMetric.Derived.toDerivedPlan(
        expression: DerivedExpression,
        prior: Map<String, ElasticsearchAggregationMetric>,
        derivedRefIndexes: MutableMap<String, Int>,
    ): ElasticsearchAggregationMetric.Derived {
        val bucketsPath = linkedMapOf<String, String>()
        val source = "def value = ${expression.toScript(prior, derivedRefIndexes, bucketsPath)}; " +
            "value == null || !Double.isFinite(value) ? null : value"
        return ElasticsearchAggregationMetric.Derived(
            alias,
            bucketsPath,
            Script.of { it.source { s -> s.scriptString(source) } },
        )
    }

    /**
     * Serializes a derived expression to its bucket_script Painless body while registering the
     * referenced sibling aggregations in [bucketsPath]. A [DerivedExpression.MetricRef] contributes
     * a `vN` value entry and — for metrics whose empty-set semantics differ from a missing value —
     * an additional `cN` value-count entry the script guards on.
     *
     * Guards use a NaN sentinel instead of null: Painless throws on null arithmetic operands, and an
     * empty-set sum is a value (0.0), not a gap, so the count guard must stay in double arithmetic
     * (`Double.NaN`). Every reference is cast `(double)` — Painless has no `as` cast operator, and
     * plain `_count` paths arrive as Longs (Long/Long division is integer division). Division relies
     * on IEEE semantics (x / 0.0 → ±Infinity) instead of an explicit zero guard, and the final
     * `!Double.isFinite` wrap unifies NaN and ±Infinity to null.
     */
    private fun DerivedExpression.toScript(
        prior: Map<String, ElasticsearchAggregationMetric>,
        derivedRefIndexes: MutableMap<String, Int>,
        bucketsPath: MutableMap<String, String>,
    ): String = when (this) {
        is DerivedExpression.MetricRef -> {
            val target = prior.getValue(metric)
            val (valuePath, countPath) = target.referencePaths()
            val index = derivedRefIndexes.getOrPut(metric) { derivedRefIndexes.size }
            bucketsPath["v$index"] = valuePath
            if (countPath == null) {
                "((double) params.v$index)"
            } else {
                bucketsPath["c$index"] = countPath
                // empty-set guard: zero count -> NaN sentinel (null would throw in Painless arithmetic)
                "(((double) params.c$index) == 0.0 ? Double.NaN : ((double) params.v$index))"
            }
        }

        is DerivedExpression.Constant -> value.toString()

        is DerivedExpression.Binary -> when (operator) {
            AggregationExpressionOperator.ADD ->
                "(${left.toScript(
                    prior,
                    derivedRefIndexes,
                    bucketsPath
                )} + ${right.toScript(prior, derivedRefIndexes, bucketsPath)})"
            AggregationExpressionOperator.SUBTRACT ->
                "(${left.toScript(
                    prior,
                    derivedRefIndexes,
                    bucketsPath
                )} - ${right.toScript(prior, derivedRefIndexes, bucketsPath)})"
            AggregationExpressionOperator.MULTIPLY ->
                "(${left.toScript(
                    prior,
                    derivedRefIndexes,
                    bucketsPath
                )} * ${right.toScript(prior, derivedRefIndexes, bucketsPath)})"
            AggregationExpressionOperator.DIVIDE -> {
                val leftScript = left.toScript(prior, derivedRefIndexes, bucketsPath)
                val rightScript = right.toScript(prior, derivedRefIndexes, bucketsPath)
                // all-double operands follow IEEE: x / 0.0 -> ±Infinity, unified to null by the final wrap
                "($leftScript / $rightScript)"
            }
        }
    }

    /**
     * Resolves (valuePath, countPath?) of a referenced metric plan; a non-null countPath means the
     * script needs the empty-set guard. Filtered wrapper names are based on the referenced metric's
     * own alias: [metricFilterAggregationName] with that alias. Metrics under that wrapper are
     * referenced with the `>` separator — buckets_path resolves `a.b` against sibling aggregation
     * *names* (a dot would look for a sibling literally named `a.b`), while `a>b` descends into the
     * single-bucket wrapper [a]. Filtered Counts keep the filter aggregation named [alias] itself,
     * so their doc_count is `alias._count`.
     */
    private fun ElasticsearchAggregationMetric.referencePaths(): Pair<String, String?> {
        val scope = if (filter == null) "" else "${metricFilterAggregationName(alias)}>"
        return when (this) {
            is ElasticsearchAggregationMetric.Count ->
                // unfiltered: the bucket's own doc_count; filtered: the doc_count of the filter aggregation named alias
                (if (filter == null) "_count" else "$alias._count") to null

            is ElasticsearchAggregationMetric.Numeric -> {
                val value = when (function) {
                    AggregationFunction.SUM, AggregationFunction.AVG,
                    AggregationFunction.MIN, AggregationFunction.MAX,
                    -> "$alias.value"

                    AggregationFunction.STDDEV -> "$alias.std_deviation_population"
                    AggregationFunction.VARIANCE -> "$alias.variance_population"
                }
                "$scope$value" to "$scope$valueCountAlias.value"
            }

            is ElasticsearchAggregationMetric.Percentile -> "$scope$alias[$percentile]" to "$scope$valueCountAlias.value"
            is ElasticsearchAggregationMetric.DistinctCount -> "$scope$alias.value" to null // cardinality is never null (may be 0)
            is ElasticsearchAggregationMetric.Derived -> "$alias.value" to null // prior bucket_script output; null -> gap -> skip
            is ElasticsearchAggregationMetric.Any -> error("Derived metric cannot reference ANY metric [$alias].")
        }
    }

    private inner class RuntimeExpressionCompiler(
        private val parent: QueryField?,
        private val physicalParent: QueryField?,
        private val schema: QueryModelSchema,
    ) {
        private val source = StringBuilder()
        private val params = linkedMapOf<String, JsonData>()
        private var nextId = 0

        fun compile(expression: AggregationExpression): RuntimeField {
            val result = append(expression)
            source.append("if ($result != null) { emit($result.doubleValue()); }")
            return RuntimeField.of { runtime ->
                runtime.type(RuntimeFieldType.Double)
                    .script(
                        Script.of { script ->
                            script.lang(ScriptLanguage.Painless)
                                .source { it.scriptString(source.toString()) }
                                .params(params)
                        },
                    )
            }
        }

        private fun append(expression: AggregationExpression): String = when (expression) {
            is AggregationExpression.Field -> appendField(expression.field)
            is AggregationExpression.Constant -> appendConstant(expression.value)
            is AggregationExpression.Binary -> appendBinary(expression)
            else -> error("Unsupported aggregation expression: ${expression::class.java.name}.")
        }

        private fun appendField(field: QueryField): String {
            val id = nextId++
            val value = "v$id"
            val fieldVariable = "f$id"
            val raw = "r$id"
            val candidate = "c$id"
            val parameter = "f$id"
            params[parameter] = JsonData.of(
                field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC),
            )
            source.append("def $value=null;")
            source.append("String $fieldVariable=params.$parameter;")
            source.append("if(doc.containsKey($fieldVariable)&&doc[$fieldVariable].size() == 1){")
            source.append("def $raw=doc[$fieldVariable].value;")
            source.append("if ($raw instanceof Number) {")
            source.append("double $candidate=((Number)$raw).doubleValue();")
            source.append("if(Double.isFinite($candidate)){$value=$candidate;}")
            source.append("}")
            source.append("}")
            return value
        }

        private fun appendConstant(constant: Double): String {
            val id = nextId++
            val value = "v$id"
            val parameter = "n$id"
            params[parameter] = JsonData.of(constant)
            source.append("def $value=((Number)params.$parameter).doubleValue();")
            return value
        }

        private fun appendBinary(binary: AggregationExpression.Binary): String {
            val left = append(binary.left)
            val right = append(binary.right)
            val id = nextId++
            val value = "v$id"
            val candidate = "c$id"
            val divisionGuard = if (binary.operator == AggregationExpressionOperator.DIVIDE) {
                " && $right.doubleValue() != 0.0"
            } else {
                ""
            }
            source.append("def $value=null;")
            source.append("if ($left != null && $right != null$divisionGuard) {")
            source.append(
                "double $candidate=$left.doubleValue() ${binary.operator.painlessOperator} " +
                    "$right.doubleValue();",
            )
            source.append("if(Double.isFinite($candidate)){$value=$candidate;}")
            source.append("}")
            return value
        }
    }

    private val AggregationExpressionOperator.painlessOperator: String
        get() = when (this) {
            AggregationExpressionOperator.ADD -> "+"
            AggregationExpressionOperator.SUBTRACT -> "-"
            AggregationExpressionOperator.MULTIPLY -> "*"
            AggregationExpressionOperator.DIVIDE -> "/"
        }

    private fun QueryField.resolve(
        parent: QueryField?,
        physicalParent: QueryField?,
        schema: QueryModelSchema,
        capability: QueryCapability,
    ): String {
        val physical = schema.physicalField(this, capability, parent)
        if (physicalParent != null && physical.relativeTo(physicalParent) == null) {
            throw QuerySchemaValidationException("Physical field [$physical] is outside its nested scope.")
        }
        return physical.path
    }

    private fun QueryValueSchema.temporalSemantic(): Temporal? {
        val values = when (kind) {
            QueryValueKind.ARRAY -> listOfNotNull(items)
            QueryValueKind.UNION -> alternatives.filter { it.kind != QueryValueKind.NULL }
            else -> listOf(this)
        }
        return values.map { value ->
            if (value !== this) value.temporalSemantic() else value.semanticType as? Temporal
        }.distinct().singleOrNull()
    }

    private val TimeUnit.epochFactors: Pair<Long, Long>
        get() = when (this) {
            TimeUnit.NANOSECONDS -> 1L to 1_000_000L
            TimeUnit.MICROSECONDS -> 1L to 1_000L
            TimeUnit.MILLISECONDS -> 1L to 1L
            TimeUnit.SECONDS -> 1_000L to 1L
            TimeUnit.MINUTES -> 60_000L to 1L
            TimeUnit.HOURS -> 3_600_000L to 1L
            TimeUnit.DAYS -> 86_400_000L to 1L
        }
}
