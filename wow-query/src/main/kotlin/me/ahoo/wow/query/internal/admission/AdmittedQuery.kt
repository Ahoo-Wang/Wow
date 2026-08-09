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

package me.ahoo.wow.query.internal.admission

import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.analytics.AnalyticsBucketWindow
import me.ahoo.wow.api.query.analytics.AnalyticsCompleteness
import me.ahoo.wow.api.query.analytics.AnalyticsConsistency
import me.ahoo.wow.api.query.analytics.AnalyticsGrouping
import me.ahoo.wow.api.query.analytics.AnalyticsMetric
import me.ahoo.wow.api.query.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.CaseSensitivity
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections

internal data class QueryAdmissionLimits(
    val maxConditionDepth: Int = 32,
    val maxConditionNodes: Int = 1024,
    val maxChildrenPerNode: Int = 128,
    val maxFieldLength: Int = 512,
    val maxStringLength: Int = 65_536,
    val maxCollectionSize: Int = 1024,
    val maxObjectFields: Int = 256,
    val maxValueDepth: Int = 16,
    val maxValueNodes: Int = 16_384,
    val maxNumericPrecision: Int = 1024,
    val maxByteArrayLength: Int = 65_536,
    val maxValuePayloadBytes: Long = 4L * 1024 * 1024,
    val maxProjectionFields: Int = 128,
    val maxSortFields: Int = 32,
    val maxOptions: Int = 8,
) {
    init {
        require(
            listOf(
                maxConditionDepth,
                maxConditionNodes,
                maxChildrenPerNode,
                maxFieldLength,
                maxStringLength,
                maxCollectionSize,
                maxObjectFields,
                maxValueDepth,
                maxValueNodes,
                maxNumericPrecision,
                maxByteArrayLength,
                maxProjectionFields,
                maxSortFields,
                maxOptions,
            ).all { it > 0 },
        ) {
            "Query admission limits must be positive."
        }
        require(maxValuePayloadBytes > 0) {
            "Query value payload limit must be positive."
        }
    }

    companion object {
        val DEFAULT: QueryAdmissionLimits = QueryAdmissionLimits()
    }
}

internal data class AdmittedQueryInvocation(
    val target: QueryTarget,
    val operation: QueryOperation,
    val resultShape: QueryResultShape,
    val input: AdmittedQueryInput,
)

internal sealed interface AdmittedQueryInput {
    data class Single(val query: AdmittedRecordQuery) : AdmittedQueryInput

    data class Stream(
        val query: AdmittedRecordQuery,
        val limit: Int,
    ) : AdmittedQueryInput

    data class Page(
        val query: AdmittedRecordQuery,
        val page: AdmittedPage,
    ) : AdmittedQueryInput

    data class Count(val condition: AdmittedCondition) : AdmittedQueryInput

    data class Analytics(val query: AnalyticsQuery) : AdmittedQueryInput

    data class AnalyticsWire(val query: AdmittedAnalyticsQuery) : AdmittedQueryInput
}

internal class AdmittedAnalyticsQuery(
    val condition: AdmittedCondition,
    val grouping: AnalyticsGrouping,
    metrics: Iterable<AnalyticsMetric>,
    val window: AnalyticsBucketWindow,
    val numericPolicy: AnalyticsNumericPolicy?,
    val consistency: AnalyticsConsistency,
    val completeness: AnalyticsCompleteness,
) {
    val metrics: List<AnalyticsMetric> = Collections.unmodifiableList(metrics.toList())
}

internal class AdmittedRecordQuery(
    val condition: AdmittedCondition,
    val projection: AdmittedProjection,
    sort: Iterable<AdmittedSort>,
) {
    val sort: List<AdmittedSort> = Collections.unmodifiableList(sort.toList())

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AdmittedRecordQuery &&
            condition == other.condition &&
            projection == other.projection &&
            sort == other.sort

    override fun hashCode(): Int = 31 * (31 * condition.hashCode() + projection.hashCode()) + sort.hashCode()
}

internal class AdmittedProjection(
    include: Iterable<String>,
    exclude: Iterable<String>,
) {
    val include: List<String> = Collections.unmodifiableList(include.toList())
    val exclude: List<String> = Collections.unmodifiableList(exclude.toList())

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AdmittedProjection &&
            include == other.include &&
            exclude == other.exclude

    override fun hashCode(): Int = 31 * include.hashCode() + exclude.hashCode()
}

internal data class AdmittedSort(
    val field: String,
    val direction: Sort.Direction,
)

internal data class AdmittedPage(
    val index: Int,
    val size: Int,
    val offset: Long,
)

internal sealed interface AdmittedConditionValue {
    data object Absent : AdmittedConditionValue

    /** Legacy RAW marker; no driver object crosses the admission boundary. */
    data object NativeUnbound : AdmittedConditionValue

    data class QueryValue(val value: NormalizedValue) : AdmittedConditionValue

    data class TimeOfDay(val value: LocalTime) : AdmittedConditionValue

    data class Deletion(val value: DeletionState) : AdmittedConditionValue
}

internal data class AdmittedConditionOptions(
    val caseSensitivity: CaseSensitivity = CaseSensitivity.SENSITIVE,
    val zoneId: ZoneId? = null,
    val datePattern: AdmittedDatePattern? = null,
)

internal class AdmittedDatePattern(
    val formatter: DateTimeFormatter,
    descriptor: String,
) {
    private val signature: List<Any?> = listOf(
        descriptor,
        formatter.locale,
        formatter.decimalStyle,
        formatter.resolverStyle,
        formatter.chronology,
        formatter.zone,
        formatter.resolverFields,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is AdmittedDatePattern && signature == other.signature

    override fun hashCode(): Int = signature.hashCode()
}

internal class AdmittedCondition(
    val field: String,
    val operator: Operator,
    val value: AdmittedConditionValue,
    children: Iterable<AdmittedCondition>,
    val options: AdmittedConditionOptions,
) {
    val children: List<AdmittedCondition> = Collections.unmodifiableList(children.toList())

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AdmittedCondition &&
            field == other.field &&
            operator == other.operator &&
            value == other.value &&
            children == other.children &&
            options == other.options

    override fun hashCode(): Int {
        var result = field.hashCode()
        result = 31 * result + operator.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + children.hashCode()
        result = 31 * result + options.hashCode()
        return result
    }
}
