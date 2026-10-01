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
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.inputExpression
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortCompiler.toSortOrder
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.ResolvedField
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

internal fun AggregationGroup.toSource(
    sort: Sort,
    index: Int,
    admitted: AdmittedQuery<*>,
    runtimeMappings: MutableMap<String, RuntimeField>,
): NamedValue<CompositeAggregationSource> {
    inputExpression?.let { return NamedValue.of(alias, expressionSource(it, sort, index, admitted, runtimeMappings)) }
    val source = when (this) {
        is AggregationGroup.Terms -> {
            val declaredMissingKey = missingKey
            if (declaredMissingKey == null) {
                CompositeAggregationSource.of {
                    it.terms { terms ->
                        terms.field(admitted.physicalPath(checkNotNull(field))).order(sort.direction.toSortOrder())
                    }
                }
            } else {
                val runtimeFieldName = "__wow_missing_terms_$index"
                runtimeMappings[runtimeFieldName] = missingKeyRuntimeField(
                    admitted.physicalPath(checkNotNull(field)),
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
                histogram.field(admitted.physicalPath(checkNotNull(field)))
                    .interval(interval)
                    .order(sort.direction.toSortOrder())
            }
        }

        is AggregationGroup.DateHistogram -> CompositeAggregationSource.of {
            it.dateHistogram { dateHistogram ->
                val native = groupsNatively(admitted)
                dateHistogram.field(
                    if (native) admitted.physicalPath(field) else dateField(index, admitted, runtimeMappings)
                )
                if (unit == AggregationDateUnit.SECOND) {
                    dateHistogram.fixedInterval { interval -> interval.time("1s") }
                } else {
                    dateHistogram.calendarInterval { interval -> interval.time(unit.name.lowercase()) }
                }
                // A numeric field refuses any `time_zone`, even `UTC`; without one it buckets in UTC.
                if (!native) dateHistogram.timeZone(timeZone)
                dateHistogram.order(sort.direction.toSortOrder())
            }
        }

        is AggregationGroup.DatePart -> CompositeAggregationSource.of {
            val runtimeFieldName = "__wow_date_part_$index"
            runtimeMappings[runtimeFieldName] = datePartRuntimeField(admitted)
            it.terms { terms -> terms.field(runtimeFieldName).order(sort.direction.toSortOrder()) }
        }
    }
    return NamedValue.of(alias, source)
}

/**
 * The calendar part of a single-valued temporal field as a long, computed in the group's time zone from the same
 * temporal encodings a date histogram accepts; records with no value emit nothing and form no bucket.
 */
/** A TERMS or HISTOGRAM source over the group's computed input, a request-local runtime field. */
private fun AggregationGroup.expressionSource(
    input: AggregationExpression,
    sort: Sort,
    index: Int,
    admitted: AdmittedQuery<*>,
    runtimeMappings: MutableMap<String, RuntimeField>,
): CompositeAggregationSource {
    val runtimeFieldName = "__wow_group_expression_$index"
    runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(admitted).compile(input)
    return CompositeAggregationSource.of {
        when (this) {
            is AggregationGroup.Histogram -> it.histogram { histogram ->
                histogram.field(runtimeFieldName).interval(interval).order(sort.direction.toSortOrder())
            }
            else -> it.terms { terms -> terms.field(runtimeFieldName).order(sort.direction.toSortOrder()) }
        }
    }
}

private fun AggregationGroup.DatePart.datePartRuntimeField(admitted: AdmittedQuery<*>): RuntimeField {
    val resolved = admitted.field(field)
    val params = mutableMapOf(
        "field" to JsonData.of(admitted.physicalPath(field)),
        "zone" to JsonData.of(timeZone),
        "part" to JsonData.of(part.name),
    )
    val source = when (val temporal = resolved.temporal) {
        Temporal.Date -> DATE_PART_OF_DATE_SCRIPT
        is Temporal.Epoch -> {
            val (multiplier, divisor) = temporal.timeUnit.epochFactors
            params["multiplier"] = JsonData.of(multiplier)
            params["divisor"] = JsonData.of(divisor)
            DATE_PART_OF_EPOCH_SCRIPT
        }
        is Temporal.Formatted, null -> resolved.noInstantEncoding()
    }
    return RuntimeField.of { runtime ->
        runtime.type(RuntimeFieldType.Long)
            .script(
                Script.of { script ->
                    script.lang(ScriptLanguage.Painless)
                        .source { it.scriptString(source) }
                        .params(params)
                },
            )
    }
}

/** Emits the calendar part `params.part` of the instant [millis] (a script variable) in `params.zone`. */
private fun partEmit(millis: String): String = """
    ZonedDateTime local = Instant.ofEpochMilli($millis).atZone(ZoneId.of(params.zone));
    String part = params.part;
    if (part == 'DAY_OF_WEEK') {
        emit(local.getDayOfWeek().getValue());
    } else if (part == 'DAY_OF_MONTH') {
        emit(local.getDayOfMonth());
    } else if (part == 'HOUR_OF_DAY') {
        emit(local.getHour());
    } else {
        emit(local.getMonthValue());
    }
""".trimIndent()

private fun AggregationGroup.DateHistogram.dateField(
    index: Int,
    admitted: AdmittedQuery<*>,
    runtimeMappings: MutableMap<String, RuntimeField>,
): String {
    val resolved = admitted.field(field)
    val physicalPath = admitted.physicalPath(field)
    return when (val temporal = resolved.temporal) {
        Temporal.Date -> physicalPath
        is Temporal.Epoch -> "__wow_date_histogram_$index".also { runtimeFieldName ->
            runtimeMappings[runtimeFieldName] = epochDateRuntimeField(physicalPath, temporal.timeUnit)
        }
        is Temporal.Formatted, null -> resolved.noInstantEncoding()
    }
}

/**
 * Whether a date histogram can run on the field itself rather than on a Painless runtime date computed per document:
 * a single-valued epoch in milliseconds that Elasticsearch stores as an integral number (Wow's `createTime`,
 * `eventTime` and the like are `long`), grouped in UTC. `date_histogram` reads a numeric field's values as epoch
 * milliseconds, and the buckets are those of the script (an integral value has no fraction to floor). A numeric field
 * takes no `time_zone`, so a group in any other zone keeps the script, as does any other epoch (another unit, an
 * array, a field inside a nested element).
 */
private fun AggregationGroup.DateHistogram.groupsNatively(admitted: AdmittedQuery<*>): Boolean =
    admitted.field(field).isNativeEpochMillis() && ZoneId.of(timeZone).isUtc()

private fun ZoneId.isUtc(): Boolean = rules.isFixedOffset && rules.getOffset(Instant.EPOCH) == ZoneOffset.UTC

internal fun ResolvedField.isNativeEpochMillis(): Boolean {
    val epoch = temporal as? Temporal.Epoch ?: return false
    val types = storageTypes
    return epoch.timeUnit == TimeUnit.MILLISECONDS &&
        cardinality == QueryCardinality.SINGLE &&
        physicalScope == null &&
        elementAncestors.isEmpty() &&
        !types.isNullOrEmpty() &&
        types.all { it.value in INTEGRAL_STORAGE_TYPES }
}

private val INTEGRAL_STORAGE_TYPES = setOf("long", "integer", "short", "byte")

private fun epochDateRuntimeField(physicalPath: String, timeUnit: TimeUnit): RuntimeField {
    val (multiplier, divisor) = timeUnit.epochFactors
    val params = mapOf(
        "field" to JsonData.of(physicalPath),
        "multiplier" to JsonData.of(multiplier),
        "divisor" to JsonData.of(divisor),
    )
    return RuntimeField.of { runtime ->
        runtime.type(RuntimeFieldType.Date)
            .script(
                Script.of { script ->
                    script.lang(ScriptLanguage.Painless)
                        .source { it.scriptString(EPOCH_DATE_SCRIPT) }
                        .params(params)
                },
            )
    }
}

/**
 * Reads the single epoch value of `doc[field]` in `multiplier` / `divisor` units as epoch milliseconds
 * `epochMillis`, floored, then runs [onMillis]; non-finite, fractional and overflowing values run nothing.
 * [field], [multiplier] and [divisor] are Painless expressions; a script that reads several epochs names
 * each one's own.
 */
internal fun epochMillisScript(
    onMillis: String,
    field: String = "field",
    multiplier: String = "params.multiplier",
    divisor: String = "params.divisor",
): String = """
            def raw = doc[$field].value;
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
                        long divisor = ((Number) $divisor).longValue();
                        long millis = epoch / divisor;
                        if (epoch < 0L && epoch % divisor != 0L) {
                            millis -= 1L;
                        }
                        long multiplier = ((Number) $multiplier).longValue();
                        if (
                            millis <= Long.MAX_VALUE / multiplier &&
                            millis >= Long.MIN_VALUE / multiplier
                        ) {
                            long epochMillis = millis * multiplier;
                            $onMillis
                        }
                    }
                }
            }
""".trimIndent()

/*
 * The scripts are built once: interpolating the shared fragments makes them non-constant, and rebuilding
 * about a kilobyte of script text per group showed up in aggregation compile time.
 */
private val EPOCH_DATE_SCRIPT = """
        String field = params.field;
        if (doc.containsKey(field) && doc[field].size() == 1) {
            ${epochMillisScript("emit(epochMillis);")}
        }
""".trimIndent()

private val DATE_PART_OF_DATE_SCRIPT = datePartScript(
    "long instantMillis = doc[field].value.toInstant().toEpochMilli();\n" + partEmit("instantMillis"),
)

private val DATE_PART_OF_EPOCH_SCRIPT = datePartScript(epochMillisScript(partEmit("epochMillis")))

private fun datePartScript(read: String): String =
    "String field = params.field;\n" +
        "if (doc.containsKey(field) && doc[field].size() == 1) {\n" + read + "\n}"

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

/** Admission admits a date group or date difference only on a date or epoch field, so this cannot be reached. */
internal fun ResolvedField.noInstantEncoding(): Nothing =
    error("Admission resolved [$logicalField] without an instant encoding.")

internal val TimeUnit.epochFactors: Pair<Long, Long>
    get() = when (this) {
        TimeUnit.NANOSECONDS -> 1L to 1_000_000L
        TimeUnit.MICROSECONDS -> 1L to 1_000L
        TimeUnit.MILLISECONDS -> 1L to 1L
        TimeUnit.SECONDS -> 1_000L to 1L
        TimeUnit.MINUTES -> 60_000L to 1L
        TimeUnit.HOURS -> 3_600_000L to 1L
        TimeUnit.DAYS -> 86_400_000L to 1L
    }
