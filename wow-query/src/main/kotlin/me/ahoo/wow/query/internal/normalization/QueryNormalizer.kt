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

package me.ahoo.wow.query.internal.normalization

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.internal.admission.AdmittedCondition
import me.ahoo.wow.query.internal.admission.AdmittedConditionValue
import me.ahoo.wow.query.internal.admission.AdmittedPage
import me.ahoo.wow.query.internal.admission.AdmittedProjection
import me.ahoo.wow.query.internal.admission.AdmittedQueryInput
import me.ahoo.wow.query.internal.admission.AdmittedQueryInvocation
import me.ahoo.wow.query.internal.admission.AdmittedRecordQuery
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.value.NonEmptyList
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.LinkedHashSet

internal class QueryNormalizer(
    private val clock: Clock,
) {
    fun normalize(invocation: AdmittedQueryInvocation): NormalizedQueryInvocation {
        val session = NormalizationSession(clock.instant())
        val inputPath = QueryRejectionPath.ROOT.property("input")
        val normalizedInput =
            when (val input = invocation.input) {
                is AdmittedQueryInput.Single -> NormalizedQueryInput.Single(
                    normalizeRecordQuery(input.query, inputPath.property("query"), session),
                )

                is AdmittedQueryInput.Stream -> NormalizedQueryInput.Stream(
                    query = normalizeRecordQuery(input.query, inputPath.property("query"), session),
                    limit = input.limit,
                )

                is AdmittedQueryInput.Page -> NormalizedQueryInput.Page(
                    query = normalizeRecordQuery(input.query, inputPath.property("query"), session),
                    page = input.page.normalize(),
                )

                is AdmittedQueryInput.Count -> NormalizedQueryInput.Count(
                    normalizeCondition(
                        input.condition,
                        inputPath.property("condition"),
                        elementScope = null,
                        session,
                    ),
                )

                is AdmittedQueryInput.Analytics -> NormalizedQueryInput.Analytics(input.query)
            }
        return NormalizedQueryInvocation(
            target = invocation.target,
            operation = invocation.operation,
            resultShape = invocation.resultShape,
            input = normalizedInput,
        )
    }

    private fun normalizeRecordQuery(
        query: AdmittedRecordQuery,
        path: QueryRejectionPath,
        session: NormalizationSession,
    ): NormalizedRecordQuery =
        NormalizedRecordQuery(
            userCondition = normalizeCondition(
                query.condition,
                path.property("condition"),
                elementScope = null,
                session,
            ),
            projection = normalizeProjection(query.projection),
            sort = query.sort.map { admittedSort ->
                NormalizedSort(
                    field = normalizeField(admittedSort.field, elementScope = null),
                    direction =
                    when (admittedSort.direction) {
                        Sort.Direction.ASC -> NormalizedSortDirection.ASC
                        Sort.Direction.DESC -> NormalizedSortDirection.DESC
                    },
                )
            },
        )

    private fun normalizeProjection(projection: AdmittedProjection): NormalizedProjection {
        if (projection.include.isNotEmpty() && projection.exclude.isNotEmpty()) {
            return NormalizedProjection.Mixed(
                include = checkNotNull(NonEmptyList.from(projection.include.map { normalizeField(it, null) })),
                exclude = checkNotNull(NonEmptyList.from(projection.exclude.map { normalizeField(it, null) })),
            )
        }
        if (projection.include.isNotEmpty()) {
            return NormalizedProjection.Include(
                checkNotNull(NonEmptyList.from(projection.include.map { normalizeField(it, null) })),
            )
        }
        if (projection.exclude.isNotEmpty()) {
            return NormalizedProjection.Exclude(
                checkNotNull(NonEmptyList.from(projection.exclude.map { normalizeField(it, null) })),
            )
        }
        return NormalizedProjection.All
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun normalizeCondition(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
        session: NormalizationSession,
    ): NormalizedCondition {
        validateElementScopedField(condition, path, elementScope)
        return when (condition.operator) {
            Operator.AND -> normalizeJunction(
                JunctionOperator.AND,
                normalizeChildren(condition, path, elementScope, session),
            )

            Operator.OR -> normalizeJunction(
                JunctionOperator.OR,
                normalizeChildren(condition, path, elementScope, session),
            )

            Operator.NOR -> normalizeJunction(
                JunctionOperator.NOR,
                normalizeChildren(condition, path, elementScope, session),
            )

            Operator.ID -> systemPredicate(
                SystemFieldKind.IDENTITY,
                PredicateOperator.EQ,
                condition.queryValue(),
                condition,
                path,
                elementScope,
            )

            Operator.IDS -> systemCollectionPredicate(
                SystemFieldKind.IDENTITY,
                PredicateOperator.IN,
                condition,
                path,
                elementScope,
            )

            Operator.AGGREGATE_ID -> systemPredicate(
                SystemFieldKind.AGGREGATE_ID,
                PredicateOperator.EQ,
                condition.queryValue(),
                condition,
                path,
                elementScope,
            )

            Operator.AGGREGATE_IDS -> systemCollectionPredicate(
                SystemFieldKind.AGGREGATE_ID,
                PredicateOperator.IN,
                condition,
                path,
                elementScope,
            )

            Operator.TENANT_ID -> systemPredicate(
                SystemFieldKind.TENANT_ID,
                PredicateOperator.EQ,
                condition.queryValue(),
                condition,
                path,
                elementScope,
            )

            Operator.OWNER_ID -> systemPredicate(
                SystemFieldKind.OWNER_ID,
                PredicateOperator.EQ,
                condition.queryValue(),
                condition,
                path,
                elementScope,
            )

            Operator.SPACE_ID -> systemPredicate(
                SystemFieldKind.SPACE_ID,
                PredicateOperator.EQ,
                condition.queryValue(),
                condition,
                path,
                elementScope,
            )

            Operator.DELETED -> normalizeDeleted(condition, path, elementScope)
            Operator.ALL -> NormalizedCondition.All
            Operator.EQ -> fieldPredicate(condition, PredicateOperator.EQ, elementScope)
            Operator.NE -> fieldPredicate(condition, PredicateOperator.NE, elementScope)
            Operator.GT -> fieldPredicate(condition, PredicateOperator.GT, elementScope)
            Operator.LT -> fieldPredicate(condition, PredicateOperator.LT, elementScope)
            Operator.GTE -> fieldPredicate(condition, PredicateOperator.GTE, elementScope)
            Operator.LTE -> fieldPredicate(condition, PredicateOperator.LTE, elementScope)
            Operator.CONTAINS -> fieldPredicate(condition, PredicateOperator.CONTAINS, elementScope)
            Operator.IN -> collectionPredicate(condition, PredicateOperator.IN, elementScope)
            Operator.NOT_IN -> collectionPredicate(condition, PredicateOperator.NOT_IN, elementScope)
            Operator.BETWEEN -> fieldPredicate(condition, PredicateOperator.BETWEEN, elementScope)
            Operator.ALL_IN -> collectionPredicate(condition, PredicateOperator.ALL_IN, elementScope)
            Operator.STARTS_WITH -> fieldPredicate(condition, PredicateOperator.STARTS_WITH, elementScope)
            Operator.ENDS_WITH -> fieldPredicate(condition, PredicateOperator.ENDS_WITH, elementScope)
            Operator.ELEM_MATCH -> normalizeElementMatch(condition, path, elementScope, session)
            Operator.NULL -> fieldPredicate(condition, PredicateOperator.IS_NULL, elementScope, value = null)
            Operator.NOT_NULL -> fieldPredicate(condition, PredicateOperator.NOT_NULL, elementScope, value = null)
            Operator.TRUE -> fieldPredicate(condition, PredicateOperator.IS_TRUE, elementScope, value = null)
            Operator.FALSE -> fieldPredicate(condition, PredicateOperator.IS_FALSE, elementScope, value = null)
            Operator.EXISTS -> fieldPredicate(condition, PredicateOperator.EXISTS, elementScope)
            Operator.TODAY -> normalizeTimeRange(condition, path, TimeRangeKind.TODAY, elementScope, session)
            Operator.BEFORE_TODAY -> normalizeBeforeToday(condition, path, elementScope, session)
            Operator.TOMORROW -> normalizeTimeRange(condition, path, TimeRangeKind.TOMORROW, elementScope, session)
            Operator.THIS_WEEK -> normalizeTimeRange(condition, path, TimeRangeKind.THIS_WEEK, elementScope, session)
            Operator.NEXT_WEEK -> normalizeTimeRange(condition, path, TimeRangeKind.NEXT_WEEK, elementScope, session)
            Operator.LAST_WEEK -> normalizeTimeRange(condition, path, TimeRangeKind.LAST_WEEK, elementScope, session)
            Operator.THIS_MONTH -> normalizeTimeRange(condition, path, TimeRangeKind.THIS_MONTH, elementScope, session)
            Operator.LAST_MONTH -> normalizeTimeRange(condition, path, TimeRangeKind.LAST_MONTH, elementScope, session)
            Operator.RECENT_DAYS -> normalizeRecentDays(condition, path, elementScope, session)
            Operator.EARLIER_DAYS -> normalizeEarlierDays(condition, path, elementScope, session)
            Operator.MATCH -> NormalizedCondition.Search(
                scope = SearchScope.LegacyField(normalizeSearchScope(condition.field, elementScope)),
                text = (condition.queryValue() as NormalizedValue.Text).value,
            )

            Operator.RAW -> rejectQuery(
                QueryRejectionCategory.UNSUPPORTED_FEATURE,
                path,
                QueryRejectionCode.NATIVE_BACKEND_UNBOUND,
            )
        }
    }

    private fun validateElementScopedField(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
    ) {
        if (elementScope == null || condition.field.isEmpty()) {
            return
        }
        val segments = condition.field.split('.')
        if (elementScope.qualifiedPrefixes.any { segments == it }) {
            rejectInvalid(path.property("field"), QueryRejectionCode.INVALID_FIELD)
        }
    }

    private fun normalizeChildren(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
        session: NormalizationSession,
    ): List<NormalizedCondition> =
        condition.children.mapIndexed { index, child ->
            normalizeCondition(child, path.property("children").index(index), elementScope, session)
        }

    private fun normalizeJunction(
        operator: JunctionOperator,
        children: List<NormalizedCondition>,
    ): NormalizedCondition =
        when (operator) {
            JunctionOperator.AND -> {
                if (children.any { it == NormalizedCondition.None }) {
                    NormalizedCondition.None
                } else {
                    val effective = children.filterNot { it == NormalizedCondition.All }
                    when (effective.size) {
                        0 -> NormalizedCondition.All
                        1 -> effective.single()
                        else -> NormalizedCondition.Junction(operator, effective)
                    }
                }
            }

            JunctionOperator.OR -> {
                if (children.any { it == NormalizedCondition.All }) {
                    NormalizedCondition.All
                } else {
                    val effective = children.filterNot { it == NormalizedCondition.None }
                    when (effective.size) {
                        0 -> NormalizedCondition.None
                        1 -> effective.single()
                        else -> NormalizedCondition.Junction(operator, effective)
                    }
                }
            }

            JunctionOperator.NOR -> {
                if (children.any { it == NormalizedCondition.All }) {
                    NormalizedCondition.None
                } else {
                    val effective = children.filterNot { it == NormalizedCondition.None }
                    if (effective.isEmpty()) {
                        NormalizedCondition.All
                    } else {
                        NormalizedCondition.Junction(operator, effective)
                    }
                }
            }
        }

    private fun normalizeElementMatch(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
        session: NormalizationSession,
    ): NormalizedCondition.ElementMatch {
        val normalizedField = normalizeField(condition.field, elementScope)
        val nestedScope = elementScope.nest(normalizedField.segments)
        return NormalizedCondition.ElementMatch(
            field = normalizedField,
            condition = normalizeCondition(
                condition.children.single(),
                path.property("children").index(0),
                nestedScope,
                session,
            ),
        )
    }

    private fun fieldPredicate(
        condition: AdmittedCondition,
        operator: PredicateOperator,
        elementScope: ElementScope?,
        value: NormalizedValue? = condition.queryValue(),
    ): NormalizedCondition =
        NormalizedCondition.Predicate(
            field = normalizeField(condition.field, elementScope),
            operator = operator,
            value = value,
            options = NormalizedPredicateOptions(condition.options.caseSensitivity),
        )

    private fun collectionPredicate(
        condition: AdmittedCondition,
        operator: PredicateOperator,
        elementScope: ElementScope?,
    ): NormalizedCondition {
        val values = condition.queryValue() as NormalizedValue.ListValue
        val uniqueValues = LinkedHashSet(values.values).toList()
        if (uniqueValues.isEmpty()) {
            return if (operator == PredicateOperator.NOT_IN) {
                NormalizedCondition.All
            } else {
                NormalizedCondition.None
            }
        }
        return fieldPredicate(
            condition,
            operator,
            elementScope,
            NormalizedValue.ListValue(uniqueValues),
        )
    }

    private fun systemPredicate(
        fieldKind: SystemFieldKind,
        operator: PredicateOperator,
        value: NormalizedValue?,
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
    ): NormalizedCondition {
        ensureRootSystemField(path, elementScope)
        return NormalizedCondition.Predicate(
            LogicalField.System(fieldKind),
            operator,
            value,
            NormalizedPredicateOptions(condition.options.caseSensitivity),
        )
    }

    private fun systemCollectionPredicate(
        fieldKind: SystemFieldKind,
        operator: PredicateOperator,
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
    ): NormalizedCondition {
        ensureRootSystemField(path, elementScope)
        val values = (condition.queryValue() as NormalizedValue.ListValue).values
        val uniqueValues = LinkedHashSet(values).toList()
        if (uniqueValues.isEmpty()) {
            return NormalizedCondition.None
        }
        return systemPredicate(
            fieldKind,
            operator,
            NormalizedValue.ListValue(uniqueValues),
            condition,
            path,
            elementScope,
        )
    }

    private fun normalizeDeleted(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
    ): NormalizedCondition {
        ensureRootSystemField(path, elementScope)
        return when ((condition.value as AdmittedConditionValue.Deletion).value) {
            DeletionState.ACTIVE -> systemPredicate(
                SystemFieldKind.DELETED,
                PredicateOperator.IS_FALSE,
                null,
                condition,
                path,
                elementScope,
            )

            DeletionState.DELETED -> systemPredicate(
                SystemFieldKind.DELETED,
                PredicateOperator.IS_TRUE,
                null,
                condition,
                path,
                elementScope,
            )

            DeletionState.ALL -> NormalizedCondition.All
        }
    }

    private fun ensureRootSystemField(path: QueryRejectionPath, elementScope: ElementScope?) {
        if (elementScope != null) {
            rejectInvalid(path, QueryRejectionCode.SYSTEM_FIELD_IN_ELEMENT_SCOPE)
        }
    }

    private fun normalizeTimeRange(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        kind: TimeRangeKind,
        elementScope: ElementScope?,
        session: NormalizationSession,
    ): NormalizedCondition {
        val zone = condition.options.zoneId ?: clock.zone
        val today = session.instant.atZone(zone).toLocalDate()
        val (fromDate, toDate) =
            when (kind) {
                TimeRangeKind.TODAY -> today to today.plusDays(1)
                TimeRangeKind.TOMORROW -> today.plusDays(1) to today.plusDays(2)
                TimeRangeKind.THIS_WEEK -> weekStart(today) to weekStart(today).plusWeeks(1)
                TimeRangeKind.NEXT_WEEK -> weekStart(today).plusWeeks(1) to weekStart(today).plusWeeks(2)
                TimeRangeKind.LAST_WEEK -> weekStart(today).minusWeeks(1) to weekStart(today)
                TimeRangeKind.THIS_MONTH -> monthStart(today) to monthStart(today).plusMonths(1)
                TimeRangeKind.LAST_MONTH -> monthStart(today).minusMonths(1) to monthStart(today)
            }
        return halfOpenRange(
            condition,
            elementScope,
            fromDate.atStartOfDay(zone).toInstant(),
            toDate.atStartOfDay(zone).toInstant(),
            zone,
            path,
        )
    }

    private fun normalizeRecentDays(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
        session: NormalizationSession,
    ): NormalizedCondition {
        val zone = condition.options.zoneId ?: clock.zone
        val today = session.instant.atZone(zone).toLocalDate()
        val days = (condition.queryValue() as NormalizedValue.Int64).value
        val from = subtractDays(today, days - 1, path).atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()
        return halfOpenRange(condition, elementScope, from, to, zone, path)
    }

    private fun normalizeEarlierDays(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
        session: NormalizationSession,
    ): NormalizedCondition {
        val zone = condition.options.zoneId ?: clock.zone
        val today = session.instant.atZone(zone).toLocalDate()
        val days = (condition.queryValue() as NormalizedValue.Int64).value
        val cutoff = subtractDays(today, days - 1, path).atStartOfDay(zone).toInstant()
        return NormalizedCondition.Predicate(
            field = normalizeField(condition.field, elementScope),
            operator = PredicateOperator.LT,
            value = timeValue(cutoff, condition.options.datePattern?.formatter, zone, path),
        )
    }

    private fun normalizeBeforeToday(
        condition: AdmittedCondition,
        path: QueryRejectionPath,
        elementScope: ElementScope?,
        session: NormalizationSession,
    ): NormalizedCondition {
        val zone = condition.options.zoneId ?: clock.zone
        val today = session.instant.atZone(zone).toLocalDate()
        val time = (condition.value as AdmittedConditionValue.TimeOfDay).value
        val cutoff = today.atTime(time).atZone(zone).toInstant()
        return NormalizedCondition.Predicate(
            field = normalizeField(condition.field, elementScope),
            operator = PredicateOperator.LT,
            value = timeValue(cutoff, condition.options.datePattern?.formatter, zone, path),
        )
    }

    private fun halfOpenRange(
        condition: AdmittedCondition,
        elementScope: ElementScope?,
        from: Instant,
        to: Instant,
        zone: ZoneId,
        path: QueryRejectionPath,
    ): NormalizedCondition =
        NormalizedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                NormalizedCondition.Predicate(
                    normalizeField(condition.field, elementScope),
                    PredicateOperator.GTE,
                    timeValue(from, condition.options.datePattern?.formatter, zone, path),
                ),
                NormalizedCondition.Predicate(
                    normalizeField(condition.field, elementScope),
                    PredicateOperator.LT,
                    timeValue(to, condition.options.datePattern?.formatter, zone, path),
                ),
            ),
        )

    private fun timeValue(
        instant: Instant,
        datePattern: DateTimeFormatter?,
        zone: ZoneId,
        path: QueryRejectionPath,
    ): NormalizedValue {
        if (datePattern == null) {
            return NormalizedValue.InstantValue(instant)
        }
        val formatted = try {
            datePattern.format(instant.atZone(zone))
        } catch (error: DateTimeException) {
            rejectInvalid(
                path.property("options").key(Condition.DATE_PATTERN_OPTION_KEY),
                QueryRejectionCode.INVALID_OPTION_VALUE,
                error,
            )
        }
        return NormalizedValue.Text(formatted)
    }

    private fun normalizeField(field: String, elementScope: ElementScope?): LogicalField.Path {
        val segments = field.split('.')
        if (elementScope == null) {
            return LogicalField.Path(segments, PathBasis.ROOT)
        }
        val matchedPrefix = elementScope.qualifiedPrefixes
            .filter { prefix -> segments.startsWithPrefix(prefix) }
            .maxByOrNull(List<String>::size)
        val relative = matchedPrefix?.let { segments.drop(it.size) } ?: segments
        return LogicalField.Path(relative, PathBasis.CURRENT_ELEMENT)
    }

    private fun normalizeSearchScope(field: String, elementScope: ElementScope?): LogicalField.Path =
        normalizeField(field, elementScope)

    private fun ElementScope?.nest(relativeField: List<String>): ElementScope {
        if (this == null) {
            return ElementScope(relativeField, listOf(relativeField))
        }
        val newAbsolute = absoluteSegments + relativeField
        val newPrefixes = buildList {
            add(newAbsolute)
            qualifiedPrefixes.forEach { prefix -> add(prefix + relativeField) }
            add(relativeField)
        }.distinct()
        return ElementScope(newAbsolute, newPrefixes)
    }

    private fun AdmittedCondition.queryValue(): NormalizedValue =
        (value as AdmittedConditionValue.QueryValue).value

    private fun AdmittedPage.normalize(): NormalizedPage = NormalizedPage(index, size, offset)

    private fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun monthStart(date: LocalDate): LocalDate = date.withDayOfMonth(1)

    private fun subtractDays(
        date: LocalDate,
        days: Long,
        path: QueryRejectionPath,
    ): LocalDate =
        try {
            date.minusDays(days)
        } catch (error: DateTimeException) {
            rejectQuery(
                QueryRejectionCategory.INVALID_QUERY,
                path.property("value"),
                QueryRejectionCode.INVALID_TIME_VALUE,
                error,
            )
        }

    private fun <T> List<T>.startsWithPrefix(prefix: List<T>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix

    private fun rejectInvalid(
        path: QueryRejectionPath,
        code: QueryRejectionCode,
        cause: Throwable? = null,
    ): Nothing = rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, code, cause)

    private data class NormalizationSession(val instant: Instant)

    private data class ElementScope(
        val absoluteSegments: List<String>,
        val qualifiedPrefixes: List<List<String>>,
    )

    private enum class TimeRangeKind {
        TODAY,
        TOMORROW,
        THIS_WEEK,
        NEXT_WEEK,
        LAST_WEEK,
        THIS_MONTH,
        LAST_MONTH,
    }
}
