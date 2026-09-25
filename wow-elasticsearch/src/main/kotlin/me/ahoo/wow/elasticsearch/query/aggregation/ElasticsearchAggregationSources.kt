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
import co.elastic.clients.json.JsonData
import co.elastic.clients.util.NamedValue
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortCompiler.toSortOrder
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import java.util.concurrent.TimeUnit

internal fun AggregationGroup.toSource(
    sort: Sort,
    index: Int,
    admitted: AdmittedQuery<*>,
    runtimeMappings: MutableMap<String, RuntimeField>,
): NamedValue<CompositeAggregationSource> {
    val source = when (this) {
        is AggregationGroup.Terms -> {
            val declaredMissingKey = missingKey
            if (declaredMissingKey == null) {
                CompositeAggregationSource.of {
                    it.terms { terms ->
                        terms.field(field.physicalPath(admitted)).order(sort.direction.toSortOrder())
                    }
                }
            } else {
                val runtimeFieldName = "__wow_missing_terms_$index"
                runtimeMappings[runtimeFieldName] = missingKeyRuntimeField(
                    field.physicalPath(admitted),
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
                histogram.field(field.physicalPath(admitted))
                    .interval(interval)
                    .order(sort.direction.toSortOrder())
            }
        }

        is AggregationGroup.DateHistogram -> CompositeAggregationSource.of {
            it.dateHistogram { dateHistogram ->
                dateHistogram.field(dateField(index, admitted, runtimeMappings))
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
    index: Int,
    admitted: AdmittedQuery<*>,
    runtimeMappings: MutableMap<String, RuntimeField>,
): String {
    val resolved = admitted.field(field)
    val logicalField = resolved.logicalField
    val physicalPath = resolved.physicalField.path
    return when (val semanticType = resolved.value.temporalSemantic()) {
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
                emit(raw.toString());
                return;
            }
        }
        emit(params.missing);
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
