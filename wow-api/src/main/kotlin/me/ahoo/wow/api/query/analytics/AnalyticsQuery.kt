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

package me.ahoo.wow.api.query.analytics

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.Condition
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

/** An opaque, bounded continuation token. Clients must not inspect or modify its contents. */
@Schema(type = "string", maxLength = AnalyticsCursor.MAX_LENGTH, pattern = AnalyticsCursor.PATTERN)
class AnalyticsCursor
@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
constructor(
    @get:JsonValue
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Analytics cursor must not be blank." }
        require(value.length <= MAX_LENGTH) { "Analytics cursor must not exceed $MAX_LENGTH characters." }
        require(URL_SAFE.matches(value)) { "Analytics cursor must be URL-safe without padding." }
    }

    override fun equals(other: Any?): Boolean = this === other || other is AnalyticsCursor && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH: Int = 256
        const val PATTERN: String = "^[A-Za-z0-9._-]+$"
        private val URL_SAFE = Regex(PATTERN)
    }
}

enum class AnalyticsGroupingKind {
    GLOBAL,
    BY,
}

enum class AnalyticsMissingPolicy {
    EXCLUDE,
    AS_NULL_BUCKET,
}

data class AnalyticsDimension(
    @field:Schema(minLength = 1, maxLength = MAX_ALIAS_LENGTH)
    val alias: String,
    @field:Schema(minLength = 1, maxLength = MAX_FIELD_LENGTH)
    val field: String,
    val missingPolicy: AnalyticsMissingPolicy = AnalyticsMissingPolicy.EXCLUDE,
) {
    init {
        requireAnalyticsAlias(alias)
        requireLogicalField(field)
    }
}

/** Grouping is explicit rather than inferred from an empty dimension list. */
class AnalyticsGrouping(
    val kind: AnalyticsGroupingKind,
    dimensions: List<AnalyticsDimension> = emptyList(),
) {
    val dimensions: List<AnalyticsDimension> = immutableList(dimensions)

    init {
        when (kind) {
            AnalyticsGroupingKind.GLOBAL -> require(this.dimensions.isEmpty()) {
                "Global analytics must not declare dimensions."
            }

            AnalyticsGroupingKind.BY -> require(this.dimensions.isNotEmpty()) {
                "Grouped analytics must declare at least one dimension."
            }
        }
        require(this.dimensions.map(AnalyticsDimension::alias).distinct().size == this.dimensions.size) {
            "Analytics dimension aliases must be unique."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is AnalyticsGrouping && kind == other.kind && dimensions == other.dimensions

    override fun hashCode(): Int = 31 * kind.hashCode() + dimensions.hashCode()

    companion object {
        @JvmStatic
        fun global(): AnalyticsGrouping = AnalyticsGrouping(AnalyticsGroupingKind.GLOBAL)

        @JvmStatic
        fun by(dimensions: List<AnalyticsDimension>): AnalyticsGrouping =
            AnalyticsGrouping(AnalyticsGroupingKind.BY, dimensions)
    }
}

enum class AnalyticsMetricKind {
    DOCUMENT_COUNT,
    MIN,
    MAX,
    SUM,
    AVERAGE,
}

data class AnalyticsMetric(
    @field:Schema(minLength = 1, maxLength = MAX_ALIAS_LENGTH)
    val alias: String,
    val kind: AnalyticsMetricKind,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(minLength = 1, maxLength = MAX_FIELD_LENGTH)
    val field: String? = null,
) {
    init {
        requireAnalyticsAlias(alias)
        when (kind) {
            AnalyticsMetricKind.DOCUMENT_COUNT -> require(field == null) {
                "Document-count metric must not declare a field."
            }

            AnalyticsMetricKind.MIN,
            AnalyticsMetricKind.MAX,
            AnalyticsMetricKind.SUM,
            AnalyticsMetricKind.AVERAGE,
            -> requireLogicalField(requireNotNull(field) { "Analytics metric field is required." })
        }
    }
}

data class AnalyticsBucketWindow(
    @field:Schema(minimum = "1")
    val limit: Int,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val cursor: AnalyticsCursor? = null,
) {
    init {
        require(limit > 0) { "Analytics bucket limit must be positive." }
    }
}

enum class AnalyticsConsistency {
    EVENTUAL,
    SNAPSHOT,
}

enum class AnalyticsCompleteness {
    EXACT,
}

enum class AnalyticsNumericPromotion {
    DECIMAL128,
}

enum class AnalyticsOverflowPolicy {
    REJECT,
}

data class AnalyticsNumericPolicy(
    val promotion: AnalyticsNumericPromotion = AnalyticsNumericPromotion.DECIMAL128,
    @field:Schema(minimum = "1", maximum = "34")
    val precision: Int = DECIMAL128_PRECISION,
    @field:Schema(minimum = "0", maximum = "34")
    val scale: Int,
    val roundingMode: RoundingMode = RoundingMode.HALF_EVEN,
    val overflowPolicy: AnalyticsOverflowPolicy = AnalyticsOverflowPolicy.REJECT,
) {
    init {
        require(precision in 1..DECIMAL128_PRECISION) {
            "Analytics Decimal128 precision must be between 1 and $DECIMAL128_PRECISION."
        }
        require(scale in 0..precision) { "Analytics numeric scale must be between zero and precision." }
    }

    private companion object {
        const val DECIMAL128_PRECISION = 34
    }
}

/** Public analytics request. Backend names, physical fields and native options are deliberately absent. */
class AnalyticsQuery(
    val condition: Condition = Condition.ALL,
    val grouping: AnalyticsGrouping,
    metrics: List<AnalyticsMetric>,
    val window: AnalyticsBucketWindow,
    val numericPolicy: AnalyticsNumericPolicy? = null,
    val consistency: AnalyticsConsistency = AnalyticsConsistency.EVENTUAL,
    val completeness: AnalyticsCompleteness = AnalyticsCompleteness.EXACT,
) {
    @field:ArraySchema(minItems = 1)
    val metrics: List<AnalyticsMetric> = immutableList(metrics)

    init {
        require(this.metrics.isNotEmpty()) { "Analytics metrics must not be empty." }
        val aliases = grouping.dimensions.map(AnalyticsDimension::alias) + this.metrics.map(AnalyticsMetric::alias)
        require(aliases.distinct().size == aliases.size) { "Analytics aliases must be unique." }
        if (grouping.kind == AnalyticsGroupingKind.GLOBAL) {
            require(window.limit == 1 && window.cursor == null) {
                "Global analytics must use limit one without a cursor."
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AnalyticsQuery &&
            condition == other.condition &&
            grouping == other.grouping &&
            metrics == other.metrics &&
            window == other.window &&
            numericPolicy == other.numericPolicy &&
            consistency == other.consistency &&
            completeness == other.completeness

    override fun hashCode(): Int {
        var result = condition.hashCode()
        result = 31 * result + grouping.hashCode()
        result = 31 * result + metrics.hashCode()
        result = 31 * result + window.hashCode()
        result = 31 * result + (numericPolicy?.hashCode() ?: 0)
        result = 31 * result + consistency.hashCode()
        result = 31 * result + completeness.hashCode()
        return result
    }
}

enum class AnalyticsValueType {
    NULL,
    BOOLEAN,
    TEXT,
    INT64,
    DECIMAL,
    INSTANT,
}

/** Lossless JSON value representation for analytics keys and metrics. */
data class AnalyticsValue(
    val type: AnalyticsValueType,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val value: String?,
) {
    init {
        when (type) {
            AnalyticsValueType.NULL -> require(value == null) { "Null analytics value must not carry text." }
            AnalyticsValueType.BOOLEAN -> require(value == "true" || value == "false") {
                "Boolean analytics value must be lowercase true or false."
            }

            AnalyticsValueType.TEXT -> require(value != null) { "Text analytics value is required." }
            AnalyticsValueType.INT64 -> requireCanonicalInt64(requireNotNull(value))
            AnalyticsValueType.DECIMAL -> requireCanonicalDecimal(requireNotNull(value))
            AnalyticsValueType.INSTANT -> requireCanonicalInstant(requireNotNull(value))
        }
    }

    companion object {
        @JvmStatic
        fun nullValue(): AnalyticsValue = AnalyticsValue(AnalyticsValueType.NULL, null)

        @JvmStatic
        fun of(value: Boolean): AnalyticsValue = AnalyticsValue(AnalyticsValueType.BOOLEAN, value.toString())

        @JvmStatic
        fun of(value: String): AnalyticsValue = AnalyticsValue(AnalyticsValueType.TEXT, value)

        @JvmStatic
        fun of(value: Long): AnalyticsValue = AnalyticsValue(AnalyticsValueType.INT64, value.toString())

        @JvmStatic
        fun of(value: BigDecimal): AnalyticsValue =
            AnalyticsValue(AnalyticsValueType.DECIMAL, value.toPlainString())

        @JvmStatic
        fun of(value: Instant): AnalyticsValue = AnalyticsValue(AnalyticsValueType.INSTANT, value.toString())
    }
}

class AnalyticsBucket(
    keys: Map<String, AnalyticsValue>,
    metrics: Map<String, AnalyticsValue>,
) {
    val keys: Map<String, AnalyticsValue> = immutableAliasMap(keys)
    val metrics: Map<String, AnalyticsValue> = immutableAliasMap(metrics)

    override fun equals(other: Any?): Boolean =
        this === other || other is AnalyticsBucket && keys == other.keys && metrics == other.metrics

    override fun hashCode(): Int = 31 * keys.hashCode() + metrics.hashCode()
}

class AnalyticsPage(
    buckets: List<AnalyticsBucket>,
    val nextCursor: AnalyticsCursor?,
    val consistency: AnalyticsConsistency,
    val completeness: AnalyticsCompleteness,
) {
    val buckets: List<AnalyticsBucket> = immutableList(buckets)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AnalyticsPage &&
            buckets == other.buckets &&
            nextCursor == other.nextCursor &&
            consistency == other.consistency &&
            completeness == other.completeness

    override fun hashCode(): Int {
        var result = buckets.hashCode()
        result = 31 * result + (nextCursor?.hashCode() ?: 0)
        result = 31 * result + consistency.hashCode()
        result = 31 * result + completeness.hashCode()
        return result
    }
}

private fun requireAnalyticsAlias(value: String) {
    require(value.isNotBlank()) { "Analytics alias must not be blank." }
    require(value.length <= MAX_ALIAS_LENGTH) { "Analytics alias must not exceed $MAX_ALIAS_LENGTH characters." }
    require(value.none(Char::isISOControl)) { "Analytics alias must not contain control characters." }
    require('.' !in value && '$' !in value) { "Analytics alias must be a safe result field name." }
}

private fun requireLogicalField(value: String) {
    require(value.isNotBlank()) { "Analytics logical field must not be blank." }
    require(
        value.length <= MAX_FIELD_LENGTH
    ) { "Analytics logical field must not exceed $MAX_FIELD_LENGTH characters." }
    require(value.none(Char::isISOControl)) { "Analytics logical field must not contain control characters." }
    require(value.split('.').all(String::isNotBlank)) { "Analytics logical field must not contain empty segments." }
    require(value.split('.').none { segment -> segment.startsWith('$') }) {
        "Analytics logical field must not contain physical operator segments."
    }
}

private fun requireCanonicalInt64(value: String) {
    val parsed = value.toLongOrNull()
    require(parsed != null && parsed.toString() == value) { "Analytics Int64 value must be canonical decimal text." }
}

private fun requireCanonicalDecimal(value: String) {
    val parsed = value.toBigDecimalOrNull()
    require(parsed != null && parsed.toPlainString() == value) {
        "Analytics Decimal value must be canonical non-exponent decimal text."
    }
}

private fun requireCanonicalInstant(value: String) {
    val parsed = runCatching { Instant.parse(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) { "Analytics Instant value must be canonical ISO-8601 text." }
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun immutableAliasMap(values: Map<String, AnalyticsValue>): Map<String, AnalyticsValue> {
    values.keys.forEach(::requireAnalyticsAlias)
    val copy = LinkedHashMap<String, AnalyticsValue>(values.size)
    values.entries.sortedBy(Map.Entry<String, AnalyticsValue>::key).forEach { entry ->
        copy[entry.key] = entry.value
    }
    return Collections.unmodifiableMap(copy)
}

private const val MAX_ALIAS_LENGTH = 128
private const val MAX_FIELD_LENGTH = 512
