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
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortCompiler.toSortOrder
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
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

    data class Count(override val alias: String) : ElasticsearchAggregationMetric

    data class Numeric(
        override val alias: String,
        val function: AggregationFunction,
        val field: String,
    ) : ElasticsearchAggregationMetric {
        val valueCountAlias: String
            get() = "__wow_value_count_$alias"
    }

    data class Any(
        override val alias: String,
        val field: String,
    ) : ElasticsearchAggregationMetric
}

internal class ElasticsearchAggregationCompiler(
    private val filterCompiler: AbstractElasticsearchFilterCompiler,
) {
    fun compile(query: AggregationQuery, schema: QueryModelSchema): ElasticsearchAggregationPlan {
        val rootQuery = filterCompiler.compile(query.filter, schema)
        val elements = mutableListOf<ElasticsearchAggregationElement>()
        var logicalParent: QueryField? = null
        var resolvedParent: QueryField? = null
        query.elements.forEach { element ->
            val previousLogicalParent = logicalParent
            val previousResolvedParent = resolvedParent
            logicalParent = previousLogicalParent?.append(element.path) ?: element.path
            val currentResolvedParent = schema.field(logicalParent)
                ?.binding(QueryCapability.ELEMENT_SCOPE)
                ?.resolvedField
                ?: previousResolvedParent?.append(element.path)
                ?: logicalParent
            resolvedParent = currentResolvedParent
            val nestedPath = element.path.resolve(previousLogicalParent, schema, QueryCapability.ELEMENT_SCOPE)
            val physicalParent = QueryField(nestedPath)
            val unscopedFilter = AndFilter(
                listOf(element.filter, DeletionFilter(DeletionState.ALL)),
            )
            elements += ElasticsearchAggregationElement(
                path = nestedPath,
                filter = filterCompiler.compileScoped(
                    unscopedFilter,
                    schema,
                    logicalParent,
                    currentResolvedParent,
                    physicalParent,
                ),
            )
        }

        val runtimeMappings = linkedMapOf<String, RuntimeField>()
        val effectiveSort = query.effectiveSort()
        val groups = query.groupBy.withIndex().associateBy { it.value.alias }
        val groupSources = effectiveSort.mapNotNull { sort ->
            groups[sort.field.path]?.let { indexed ->
                indexed.value.toSource(logicalParent, sort, indexed.index, schema, runtimeMappings)
            }
        }
        val metrics = query.metrics.mapIndexed { index, metric ->
            metric.toPlan(logicalParent, index, schema, runtimeMappings)
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
        sort: Sort,
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): NamedValue<CompositeAggregationSource> {
        val source = when (this) {
            is AggregationGroup.Terms -> CompositeAggregationSource.of {
                it.terms { terms ->
                    terms.field(field.resolve(parent, schema, QueryCapability.AGGREGATE_TERMS))
                        .order(sort.direction.toSortOrder())
                }
            }

            is AggregationGroup.Histogram -> CompositeAggregationSource.of {
                it.histogram { histogram ->
                    histogram.field(field.resolve(parent, schema, QueryCapability.AGGREGATE_NUMERIC))
                        .interval(interval)
                        .order(sort.direction.toSortOrder())
                }
            }

            is AggregationGroup.DateHistogram -> CompositeAggregationSource.of {
                it.dateHistogram { dateHistogram ->
                    dateHistogram.field(dateField(parent, index, schema, runtimeMappings))
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
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): String {
        val logicalField = parent?.append(field) ?: field
        val fieldSchema = schema.field(logicalField) ?: return logicalField.path
        val physicalPath = fieldSchema.binding(QueryCapability.AGGREGATE_TEMPORAL)?.physicalField?.path
            ?: throw QuerySchemaValidationException(
                "Query field [$logicalField] does not support [${QueryCapability.AGGREGATE_TEMPORAL}].",
            )
        return when (val semanticType = fieldSchema.semanticType) {
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
            try {
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
            } catch (Exception ignored) {}
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
        index: Int,
        schema: QueryModelSchema,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): ElasticsearchAggregationMetric = when (this) {
        is AggregationMetric.Count -> ElasticsearchAggregationMetric.Count(alias)
        is AggregationMetric.Any -> ElasticsearchAggregationMetric.Any(
            alias,
            field.resolve(parent, schema, QueryCapability.AGGREGATE_TERMS),
        )
        is AggregationMetric.Numeric -> {
            val metricField = when (val expression = expression) {
                is AggregationExpression.Field -> expression.field.resolve(
                    parent,
                    schema,
                    QueryCapability.AGGREGATE_NUMERIC,
                )
                else -> "__wow_expression_$index".also { runtimeFieldName ->
                    runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(parent, schema).compile(expression)
                }
            }
            ElasticsearchAggregationMetric.Numeric(alias, function, metricField)
        }
    }

    private inner class RuntimeExpressionCompiler(
        private val parent: QueryField?,
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
                field.resolve(parent, schema, QueryCapability.AGGREGATE_NUMERIC),
            )
            source.append("def $value=null;")
            source.append("String $fieldVariable=params.$parameter;")
            source.append("try {")
            source.append("if(doc.containsKey($fieldVariable)&&doc[$fieldVariable].size() == 1){")
            source.append("def $raw=doc[$fieldVariable].value;")
            source.append("if ($raw instanceof Number) {")
            source.append("double $candidate=((Number)$raw).doubleValue();")
            source.append("if(Double.isFinite($candidate)){$value=$candidate;}")
            source.append("}")
            source.append("}")
            source.append("} catch (Exception ignored) {}")
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
        schema: QueryModelSchema,
        capability: QueryCapability,
    ): String {
        val logicalField = parent?.append(this) ?: this
        val fieldSchema = schema.field(logicalField) ?: return logicalField.path
        return fieldSchema.binding(capability)?.physicalField?.path
            ?: throw QuerySchemaValidationException("Query field [$logicalField] does not support [$capability].")
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
