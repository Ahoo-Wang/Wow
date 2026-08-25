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

package me.ahoo.wow.elasticsearch.query.snapshot

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
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterConverter
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldResolutionException
import me.ahoo.wow.elasticsearch.query.ElasticsearchFieldUsage
import me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMapping
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortConverter.toSortOrder

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

internal data class ElasticsearchAggregationMetric(
    val alias: String,
    val function: AggregationFunction?,
    val field: String?,
) {
    val valueCountAlias: String
        get() = "__wow_value_count_$alias"
}

internal class ElasticsearchAggregationCompiler(
    private val filterConverter: AbstractElasticsearchFilterConverter,
    private val mapping: ElasticsearchIndexMapping?,
) {
    fun compile(query: AggregationQuery): ElasticsearchAggregationPlan {
        val rootFilter = mapping?.resolve(query.filter) ?: query.filter
        val rootQuery = filterConverter.convert(rootFilter)
        val elements = mutableListOf<ElasticsearchAggregationElement>()
        var parent: String? = null
        query.elements.forEach { element ->
            val absolutePath = if (parent == null) element.path.value else "$parent.${element.path.value}"
            val nestedPath = mapping?.requireNested(absolutePath) ?: absolutePath
            val unscopedFilter = AndFilter(listOf(element.filter, DeletionFilter(DeletionState.ALL)))
            val filter = mapping?.resolve(unscopedFilter, absolutePath) ?: unscopedFilter
            elements += ElasticsearchAggregationElement(
                path = nestedPath,
                filter = filterConverter.convert(filter, absolutePath),
            )
            parent = absolutePath
        }

        val effectiveSort = query.effectiveSort()
        val groups = query.groupBy.associateBy(AggregationGroup::alias)
        val groupSources = effectiveSort
            .mapNotNull { sort -> groups[sort.field]?.let { it.toSource(parent, sort) } }
        val runtimeMappings = linkedMapOf<String, RuntimeField>()
        val metrics = query.metrics.mapIndexed { index, metric ->
            metric.toPlan(parent, index, runtimeMappings)
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
            metricSorted = effectiveSort.any { it.field in metricAliases },
        )
    }

    private fun AggregationGroup.toSource(parent: String?, sort: Sort): NamedValue<CompositeAggregationSource> {
        val absoluteField = field.resolve(parent)
        val source = when (this) {
            is AggregationGroup.Terms -> CompositeAggregationSource.of {
                it.terms { terms ->
                    terms.field(mapping?.resolve(absoluteField, ElasticsearchFieldUsage.EXACT) ?: absoluteField)
                        .order(sort.direction.toSortOrder())
                }
            }

            is AggregationGroup.Histogram -> CompositeAggregationSource.of {
                it.histogram { histogram ->
                    histogram.field(mapping?.resolve(absoluteField, ElasticsearchFieldUsage.RANGE) ?: absoluteField)
                        .interval(interval)
                        .order(sort.direction.toSortOrder())
                }
            }

            is AggregationGroup.DateHistogram -> CompositeAggregationSource.of {
                it.dateHistogram { dateHistogram ->
                    dateHistogram
                        .field(mapping?.resolve(absoluteField, ElasticsearchFieldUsage.RANGE) ?: absoluteField)
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

    private fun AggregationMetric.toPlan(
        parent: String?,
        index: Int,
        runtimeMappings: MutableMap<String, RuntimeField>,
    ): ElasticsearchAggregationMetric = when (this) {
        is AggregationMetric.Count -> ElasticsearchAggregationMetric(alias, function = null, field = null)
        is AggregationMetric.Numeric -> {
            val metricField = when (val expression = expression) {
                is AggregationExpression.Field -> expression.field.resolveRange(parent)
                else -> "__wow_expression_$index".also { runtimeFieldName ->
                    runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(parent).compile(expression)
                }
            }
            ElasticsearchAggregationMetric(alias, function, metricField)
        }
    }

    private fun LogicalField.resolveRange(parent: String?): String {
        val absoluteField = resolve(parent)
        return mapping?.resolve(absoluteField, ElasticsearchFieldUsage.RANGE) ?: absoluteField
    }

    private inner class RuntimeExpressionCompiler(private val parent: String?) {
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

        private fun appendField(field: LogicalField): String {
            val id = nextId++
            val value = "v$id"
            val fieldVariable = "f$id"
            val raw = "r$id"
            val candidate = "c$id"
            val parameter = "f$id"
            params[parameter] = JsonData.of(field.resolveComputed(parent))
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

    private fun LogicalField.resolveComputed(parent: String?): String {
        val absoluteField = resolve(parent)
        return try {
            mapping?.resolve(absoluteField, ElasticsearchFieldUsage.PRESENCE) ?: absoluteField
        } catch (_: ElasticsearchFieldResolutionException) {
            absoluteField
        }
    }

    private fun LogicalField.resolve(parent: String?): String =
        if (parent == null) value else "$parent.$value"
}
