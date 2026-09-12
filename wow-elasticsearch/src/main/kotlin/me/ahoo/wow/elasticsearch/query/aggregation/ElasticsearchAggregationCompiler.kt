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
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
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
        val metrics = query.metrics.mapIndexed { index, metric ->
            metric.toPlan(logicalParent, physicalParent, index, schema, runtimeMappings, now)
        }
        val metricAliases = query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
        return ElasticsearchAggregationPlan(
            rootQuery = rootQuery,
            elements = elements,
            groupSources = groupSources,
            metrics = metrics,
            runtimeMappings = runtimeMappings,
            effectiveSort = effectiveSort,
            limit = query.limit,
            metricSorted = effectiveSort.any { it.field.path in metricAliases },
        )
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
            is AggregationGroup.Terms -> CompositeAggregationSource.of {
                it.terms { terms ->
                    terms.field(field.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_TERMS))
                        .order(sort.direction.toSortOrder())
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

    private fun AggregationMetric.toPlan(
        parent: QueryField?,
        physicalParent: QueryField?,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
        now: Instant,
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

/**
 * Filter aggregations keep Elasticsearch's element-matching semantics over array fields while
 * MongoDB metric guards compare whole values, so array-valued fields and [ElementMatchFilter]
 * are rejected to mirror the MongoDB compiler's scalar-only metric filter contract.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun FilterExpression.requireScalarMetricFilterFields(
    parent: QueryField?,
    schema: QueryModelSchema,
) {
    when (this) {
        MatchAllFilter, MatchNoneFilter,
        is IdFilter, is IdsFilter, is AggregateIdFilter, is AggregateIdsFilter,
        is TenantIdFilter, is OwnerIdFilter, is SpaceIdFilter, is DeletionFilter,
        -> Unit

        is AndFilter -> operands.forEach { it.requireScalarMetricFilterFields(parent, schema) }
        is OrFilter -> operands.forEach { it.requireScalarMetricFilterFields(parent, schema) }
        is NorFilter -> operands.forEach { it.requireScalarMetricFilterFields(parent, schema) }
        is ElementMatchFilter -> throw QuerySchemaValidationException(
            "Elasticsearch metric filters cannot translate [ELEMENT_MATCH] into a filter aggregation.",
        )
        is SearchFilter -> fields.forEach { it.requireScalarMetricFilterField(parent, schema) }
        is RelativeTimeFilter -> field.requireScalarMetricFilterField(parent, schema)
        is EqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is NotEqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is GreaterThanFilter -> field.requireScalarMetricFilterField(parent, schema)
        is GreaterThanOrEqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is LessThanFilter -> field.requireScalarMetricFilterField(parent, schema)
        is LessThanOrEqualFilter -> field.requireScalarMetricFilterField(parent, schema)
        is BetweenFilter -> field.requireScalarMetricFilterField(parent, schema)
        is ContainsFilter -> field.requireScalarMetricFilterField(parent, schema)
        is StartsWithFilter -> field.requireScalarMetricFilterField(parent, schema)
        is EndsWithFilter -> field.requireScalarMetricFilterField(parent, schema)
        is InFilter -> field.requireScalarMetricFilterField(parent, schema)
        is NotInFilter -> field.requireScalarMetricFilterField(parent, schema)
        is ContainsAllFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsEmptyFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsEmptyStringFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsNotEmptyStringFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsNullFilter -> field.requireScalarMetricFilterField(parent, schema)
        is IsNotNullFilter -> field.requireScalarMetricFilterField(parent, schema)
        is ExistsFilter -> field.requireScalarMetricFilterField(parent, schema)
        is NotExistsFilter -> field.requireScalarMetricFilterField(parent, schema)
    }
}

private fun QueryField.requireScalarMetricFilterField(parent: QueryField?, schema: QueryModelSchema) {
    val logical = parent?.append(this) ?: this
    val value = schema.field(logical)?.value ?: return
    if (value.isArrayValued) {
        throw QuerySchemaValidationException(
            "Aggregation metric filter field [$logical] must be scalar; array fields are not supported in metric filters.",
        )
    }
}

private val QueryValueSchema.isArrayValued: Boolean
    get() = kind == QueryValueKind.ARRAY ||
        (kind == QueryValueKind.UNION && alternatives.any { it.kind == QueryValueKind.ARRAY })
