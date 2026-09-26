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
import co.elastic.clients.elasticsearch._types.mapping.RuntimeField
import co.elastic.clients.elasticsearch._types.mapping.RuntimeFieldType
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.AdmittedQuery

internal fun AggregationMetric.Derived.toDerivedPlan(
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
        is ElasticsearchAggregationMetric.Edge -> error(
            "Derived metric cannot reference FIRST or LAST metric [$alias]."
        )
    }
}

internal class RuntimeExpressionCompiler(private val admitted: AdmittedQuery<*>) {
    private val source = StringBuilder()
    private val params = linkedMapOf<String, JsonData>()
    private var nextId = 0

    /**
     * A script query that matches the documents whose [ExpressionFilter.expression] has a value comparing to
     * [ExpressionFilter.value] as [ExpressionFilter.comparison] asks; a document without a value never matches.
     */
    fun compileCondition(filter: ExpressionFilter): Script {
        val result = append(filter.expression)
        params["__value"] = JsonData.of(filter.value)
        val operator = when (filter.comparison) {
            ComparisonOperator.EQ -> "=="
            ComparisonOperator.NE -> "!="
            ComparisonOperator.GT -> ">"
            ComparisonOperator.GTE -> ">="
            ComparisonOperator.LT -> "<"
            ComparisonOperator.LTE -> "<="
        }
        source.append(
            "return $result != null && $result.doubleValue() $operator ((Number)params.__value).doubleValue();",
        )
        return Script.of { script ->
            script.lang(ScriptLanguage.Painless)
                .source { it.scriptString(source.toString()) }
                .params(params)
        }
    }

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
        is AggregationExpression.DateDiff -> appendDateDiff(expression)
    }

    /** `to − from` in the unit, from each field's single value in its own temporal encoding. */
    private fun appendDateDiff(dateDiff: AggregationExpression.DateDiff): String {
        val from = appendInstant(dateDiff.from)
        val to = appendInstant(dateDiff.to)
        val id = nextId++
        val value = "v$id"
        val parameter = "u$id"
        params[parameter] = JsonData.of(dateDiff.unit.millis)
        source.append("def $value=null;")
        source.append("if ($from != null && $to != null) {")
        source.append("$value=($to.doubleValue() - $from.doubleValue()) / ((Number)params.$parameter).doubleValue();")
        source.append("}")
        return value
    }

    /** The epoch milliseconds of [field]'s single value, or `null`. */
    private fun appendInstant(field: QueryField): String {
        val resolved = admitted.field(field)
        val id = nextId++
        val value = "v$id"
        val fieldVariable = "f$id"
        val raw = "r$id"
        val parameter = "f$id"
        params[parameter] = JsonData.of(resolved.physicalField.path)
        source.append("def $value=null;")
        source.append("String $fieldVariable=params.$parameter;")
        source.append("if(doc.containsKey($fieldVariable)&&doc[$fieldVariable].size() == 1){")
        source.append("def $raw=doc[$fieldVariable].value;")
        when (val temporal = resolved.temporal) {
            Temporal.Date -> source.append("$value=(double)$raw.toInstant().toEpochMilli();")
            is Temporal.Epoch -> {
                val (multiplier, divisor) = temporal.timeUnit.epochFactors
                source.append("if ($raw instanceof Number) {")
                source.append("double c$id=((Number)$raw).doubleValue() * $multiplier.0 / $divisor.0;")
                source.append("if(Double.isFinite(c$id)){$value=c$id;}")
                source.append("}")
            }
            is Temporal.Formatted, null -> resolved.noInstantEncoding()
        }
        source.append("}")
        return value
    }

    private fun appendField(field: QueryField): String {
        val id = nextId++
        val value = "v$id"
        val fieldVariable = "f$id"
        val raw = "r$id"
        val candidate = "c$id"
        val parameter = "f$id"
        params[parameter] = JsonData.of(field.physicalPath(admitted))
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

/** The absolute physical path admission resolved for this reference, which nested aggregations address. */
internal fun QueryField.physicalPath(admitted: AdmittedQuery<*>): String = admitted.field(this).physicalField.path
