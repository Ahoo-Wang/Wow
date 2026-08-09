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

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.internal.model.QueryInput
import me.ahoo.wow.query.internal.model.QueryInvocation
import me.ahoo.wow.query.internal.normalization.CaseSensitivity
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.IdentityHashMap

internal class RawAdmissionGuard(
    private val limits: QueryAdmissionLimits,
) {
    private val valueSnapshotter = RawValueSnapshotter(limits)
    private val analyticsAdmissionGuard = AnalyticsAdmissionGuard(limits)

    fun admit(invocation: QueryInvocation): AdmittedQueryInvocation {
        val session = AdmissionSession(limits)
        val inputPath = QueryRejectionPath.ROOT.property("input")
        val admittedInput =
            when (val input = invocation.input) {
                is QueryInput.Single -> AdmittedQueryInput.Single(
                    admitRecordQuery(input.query, inputPath.property("query"), session),
                )

                is QueryInput.Stream -> admitStream(input.query, inputPath.property("query"), session)
                is QueryInput.Page -> admitPage(input.query, inputPath.property("query"), session)
                is QueryInput.Count -> AdmittedQueryInput.Count(
                    admitCondition(input.condition, inputPath.property("condition"), 1, session),
                )

                is QueryInput.Analytics -> AdmittedQueryInput.Analytics(input.query)
                is QueryInput.AnalyticsWire -> AdmittedQueryInput.AnalyticsWire(
                    analyticsAdmissionGuard.admit(
                        input.query,
                        inputPath.property("query"),
                        session.budget,
                    ) { condition, path -> admitCondition(condition, path, 1, session) },
                )
            }
        return AdmittedQueryInvocation(
            target = invocation.target,
            operation = invocation.operation,
            resultShape = invocation.resultShape,
            input = admittedInput,
        )
    }

    private fun admitStream(
        query: IListQuery,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedQueryInput.Stream {
        val condition: Condition? = query.condition
        val projection: Projection? = query.projection
        val sort: List<Sort>? = query.sort
        val limit = query.limit
        requireRecordQuery(condition, projection, sort, path)
        if (limit < 0) {
            rejectInvalid(path.property("limit"), QueryRejectionCode.INVALID_LIMIT)
        }
        return AdmittedQueryInput.Stream(
            query = admitRecordQuery(
                checkNotNull(condition),
                checkNotNull(projection),
                checkNotNull(sort),
                path,
                session,
            ),
            limit = limit,
        )
    }

    private fun admitPage(
        query: IPagedQuery,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedQueryInput.Page {
        val condition: Condition? = query.condition
        val projection: Projection? = query.projection
        val sort: List<Sort>? = query.sort
        val pagination: Pagination? = query.pagination
        requireRecordQuery(condition, projection, sort, path)
        if (pagination == null) {
            rejectInvalid(path.property("pagination"), QueryRejectionCode.INVALID_PAGE)
        }
        val index = pagination.index
        val size = pagination.size
        if (index < 1 || size <= 0) {
            rejectInvalid(path.property("pagination"), QueryRejectionCode.INVALID_PAGE)
        }
        val offset = Math.multiplyExact(index.toLong() - 1, size.toLong())
        return AdmittedQueryInput.Page(
            query = admitRecordQuery(
                checkNotNull(condition),
                checkNotNull(projection),
                checkNotNull(sort),
                path,
                session,
            ),
            page = AdmittedPage(index, size, offset),
        )
    }

    private fun admitRecordQuery(
        query: ISingleQuery,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedRecordQuery {
        val condition: Condition? = query.condition
        val projection: Projection? = query.projection
        val sort: List<Sort>? = query.sort
        requireRecordQuery(condition, projection, sort, path)
        return admitRecordQuery(
            checkNotNull(condition),
            checkNotNull(projection),
            checkNotNull(sort),
            path,
            session,
        )
    }

    private fun requireRecordQuery(
        condition: Condition?,
        projection: Projection?,
        sort: List<Sort>?,
        path: QueryRejectionPath,
    ) {
        if (condition == null) {
            rejectInvalid(path.property("condition"), QueryRejectionCode.INVALID_VALUE_TYPE)
        }
        if (projection == null) {
            rejectInvalid(path.property("projection"), QueryRejectionCode.INVALID_PROJECTION)
        }
        if (sort == null) {
            rejectInvalid(path.property("sort"), QueryRejectionCode.INVALID_SORT)
        }
    }

    private fun admitRecordQuery(
        condition: Condition,
        projection: Projection,
        sort: List<Sort>,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedRecordQuery =
        AdmittedRecordQuery(
            condition = admitCondition(condition, path.property("condition"), 1, session),
            projection = admitProjection(projection, path.property("projection"), session),
            sort = admitSort(sort, path.property("sort"), session),
        )

    private fun admitProjection(
        projection: Projection,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedProjection {
        val include = projection.include
        val exclude = projection.exclude
        val admittedInclude = admitFieldList(
            include,
            path.property("include"),
            limits.maxProjectionFields,
            session,
        )
        return AdmittedProjection(
            include = admittedInclude,
            exclude = admitFieldList(
                exclude,
                path.property("exclude"),
                limits.maxProjectionFields - admittedInclude.size,
                session,
            ),
        )
    }

    private fun admitSort(
        sort: Iterable<*>,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): List<AdmittedSort> {
        val result = ArrayList<AdmittedSort>()
        val iterator = sort.iterator()
        while (iterator.hasNext()) {
            if (result.size == limits.maxSortFields) {
                rejectBudget(path, QueryRejectionCode.SORT_LIMIT_EXCEEDED)
            }
            val index = result.size
            val item = iterator.next()
            if (item !is Sort) {
                rejectInvalid(path.index(index), QueryRejectionCode.INVALID_SORT)
            }
            val fieldPath = path.index(index).property("field")
            validateField(item.field, fieldPath)
            session.budget.consumeUtf8(item.field, fieldPath)
            result += AdmittedSort(item.field, item.direction)
        }
        return Collections.unmodifiableList(result)
    }

    private fun admitFieldList(
        fields: Iterable<*>,
        path: QueryRejectionPath,
        limit: Int,
        session: AdmissionSession,
    ): List<String> {
        val result = ArrayList<String>()
        val iterator = fields.iterator()
        while (iterator.hasNext()) {
            if (result.size == limit) {
                rejectBudget(path, QueryRejectionCode.PROJECTION_LIMIT_EXCEEDED)
            }
            val index = result.size
            val field = iterator.next()
            if (field !is String) {
                rejectInvalid(path.index(index), QueryRejectionCode.INVALID_PROJECTION)
            }
            validateField(field, path.index(index))
            session.budget.consumeUtf8(field, path.index(index))
            result += field
        }
        return Collections.unmodifiableList(result)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun admitCondition(
        condition: Condition,
        path: QueryRejectionPath,
        depth: Int,
        session: AdmissionSession,
    ): AdmittedCondition {
        if (depth > limits.maxConditionDepth) {
            rejectBudget(path, QueryRejectionCode.CONDITION_DEPTH_LIMIT_EXCEEDED)
        }
        if (session.conditionNodes == limits.maxConditionNodes) {
            rejectBudget(path, QueryRejectionCode.CONDITION_NODE_LIMIT_EXCEEDED)
        }
        session.conditionNodes++
        if (session.conditionActive.put(condition, Unit) != null) {
            rejectInvalid(path, QueryRejectionCode.CYCLIC_INPUT)
        }
        try {
            val field = condition.field
            val operator = condition.operator
            val rawValue = condition.value
            val rawChildren = condition.children
            val rawOptions = condition.options
            val fieldPath = path.property("field")
            validateConditionField(field, operator, fieldPath)
            session.budget.consumeUtf8(field, fieldPath)
            val children = materializeChildren(rawChildren, path.property("children"))
            validateChildren(operator, children, path.property("children"))
            val options = admitOptions(operator, rawOptions, path.property("options"), session)
            val value = admitConditionValue(operator, rawValue, path.property("value"), session)
            val admittedChildren = children.mapIndexed { index, child ->
                admitCondition(child, path.property("children").index(index), depth + 1, session)
            }
            return AdmittedCondition(field, operator, value, admittedChildren, options)
        } finally {
            session.conditionActive.remove(condition)
        }
    }

    private fun materializeChildren(
        children: List<*>,
        path: QueryRejectionPath,
    ): List<Condition> {
        val result = ArrayList<Condition>()
        val iterator = children.iterator()
        while (iterator.hasNext()) {
            if (result.size == limits.maxChildrenPerNode) {
                rejectBudget(path, QueryRejectionCode.CHILDREN_LIMIT_EXCEEDED)
            }
            val index = result.size
            val child = iterator.next()
            if (child !is Condition) {
                rejectInvalid(path.index(index), QueryRejectionCode.INVALID_CHILDREN)
            }
            result += child
        }
        return result
    }

    private fun validateChildren(
        operator: Operator,
        children: List<Condition>,
        path: QueryRejectionPath,
    ) {
        when (operator) {
            Operator.AND,
            Operator.OR,
            Operator.NOR,
            -> if (children.isEmpty()) {
                rejectInvalid(path, QueryRejectionCode.INVALID_CHILDREN)
            }

            Operator.ELEM_MATCH -> if (children.size != 1) {
                rejectInvalid(path, QueryRejectionCode.INVALID_CHILDREN)
            }

            else -> if (children.isNotEmpty()) {
                rejectInvalid(path, QueryRejectionCode.INVALID_CHILDREN)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun admitConditionValue(
        operator: Operator,
        rawValue: Any?,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedConditionValue =
        when (operator) {
            Operator.AND,
            Operator.OR,
            Operator.NOR,
            Operator.ALL,
            Operator.ELEM_MATCH,
            Operator.NULL,
            Operator.NOT_NULL,
            Operator.TRUE,
            Operator.FALSE,
            Operator.TODAY,
            Operator.TOMORROW,
            Operator.THIS_WEEK,
            Operator.NEXT_WEEK,
            Operator.LAST_WEEK,
            Operator.THIS_MONTH,
            Operator.LAST_MONTH,
            -> AdmittedConditionValue.Absent

            Operator.ID,
            Operator.AGGREGATE_ID,
            Operator.TENANT_ID,
            Operator.OWNER_ID,
            Operator.SPACE_ID,
            Operator.CONTAINS,
            Operator.STARTS_WITH,
            Operator.ENDS_WITH,
            Operator.MATCH,
            -> AdmittedConditionValue.QueryValue(
                normalizeRequiredString(rawValue, path, session, requireNonBlank = operator == Operator.MATCH),
            )

            Operator.IDS,
            Operator.AGGREGATE_IDS,
            -> AdmittedConditionValue.QueryValue(
                valueSnapshotter.snapshotRequiredStringIterable(rawValue, path, session.budget),
            )

            Operator.IN,
            Operator.NOT_IN,
            Operator.ALL_IN,
            -> AdmittedConditionValue.QueryValue(
                valueSnapshotter.snapshotRequiredIterable(rawValue, path, session.budget),
            )

            Operator.BETWEEN -> {
                val value = valueSnapshotter.snapshotRequiredIterable(rawValue, path, session.budget)
                if (value.values.size != 2) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_ARITY)
                }
                AdmittedConditionValue.QueryValue(value)
            }

            Operator.EXISTS -> {
                session.budget.enterValue(path)
                if (rawValue !is Boolean) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
                }
                AdmittedConditionValue.QueryValue(NormalizedValue.BooleanValue(rawValue))
            }

            Operator.DELETED -> AdmittedConditionValue.Deletion(normalizeDeletionState(rawValue, path, session))
            Operator.BEFORE_TODAY -> AdmittedConditionValue.TimeOfDay(normalizeTimeOfDay(rawValue, path, session))
            Operator.RECENT_DAYS,
            Operator.EARLIER_DAYS,
            -> AdmittedConditionValue.QueryValue(
                NormalizedValue.Int64(normalizePositiveWholeNumber(rawValue, path, session)),
            )

            Operator.EQ,
            Operator.NE,
            Operator.GT,
            Operator.LT,
            Operator.GTE,
            Operator.LTE,
            -> AdmittedConditionValue.QueryValue(valueSnapshotter.snapshot(rawValue, path, session.budget))

            Operator.RAW -> AdmittedConditionValue.NativeUnbound
        }

    private fun normalizeRequiredString(
        rawValue: Any?,
        path: QueryRejectionPath,
        session: AdmissionSession,
        requireNonBlank: Boolean,
    ): NormalizedValue.Text {
        session.budget.enterValue(path)
        if (rawValue !is String) {
            rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
        }
        session.budget.consumeString(rawValue, path)
        if (requireNonBlank && rawValue.isBlank()) {
            rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
        }
        return NormalizedValue.Text(rawValue)
    }

    private fun normalizeDeletionState(
        rawValue: Any?,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): DeletionState {
        session.budget.enterValue(path)
        return when (rawValue) {
            is DeletionState -> rawValue
            is Boolean -> if (rawValue) DeletionState.DELETED else DeletionState.ACTIVE
            is String -> {
                session.budget.consumeString(rawValue, path)
                try {
                    DeletionState.valueOf(rawValue.uppercase())
                } catch (error: IllegalArgumentException) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE, error)
                }
            }
            else -> rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
        }
    }

    private fun normalizeTimeOfDay(
        rawValue: Any?,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): LocalTime {
        session.budget.enterValue(path)
        return when (rawValue) {
            is LocalTime -> rawValue
            is String -> {
                session.budget.consumeString(rawValue, path)
                try {
                    LocalTime.parse(rawValue)
                } catch (error: DateTimeParseException) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_TIME_VALUE, error)
                }
            }
            is Number -> {
                val seconds = exactLong(rawValue, path, QueryRejectionCode.INVALID_TIME_VALUE, session)
                if (seconds !in 0..86_399) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_TIME_VALUE)
                }
                LocalTime.ofSecondOfDay(seconds)
            }
            else -> rejectInvalid(path, QueryRejectionCode.INVALID_TIME_VALUE)
        }
    }

    private fun normalizePositiveWholeNumber(
        rawValue: Any?,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): Long {
        session.budget.enterValue(path)
        if (rawValue !is Number) {
            rejectInvalid(path, QueryRejectionCode.INVALID_TIME_VALUE)
        }
        val value = exactLong(rawValue, path, QueryRejectionCode.INVALID_TIME_VALUE, session)
        if (value <= 0) {
            rejectInvalid(path, QueryRejectionCode.INVALID_TIME_VALUE)
        }
        return value
    }

    private fun exactLong(
        number: Number,
        path: QueryRejectionPath,
        code: QueryRejectionCode,
        session: AdmissionSession,
    ): Long {
        if (!number.isSupported()) {
            rejectInvalid(path, code)
        }
        val numberText = session.budget.consumeNumber(number, path)
        return try {
            numberText.toBigDecimal().longValueExact()
        } catch (error: NumberFormatException) {
            rejectInvalid(path, code, error)
        } catch (error: ArithmeticException) {
            rejectInvalid(path, code, error)
        }
    }

    private fun Number.isSupported(): Boolean =
        when (this) {
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            is java.math.BigDecimal,
            is java.math.BigInteger,
            -> true
            else -> false
        }

    private fun admitOptions(
        operator: Operator,
        options: Map<*, *>,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedConditionOptions {
        var caseSensitivity = CaseSensitivity.SENSITIVE
        var zoneId: ZoneId? = null
        var datePattern: AdmittedDatePattern? = null
        var count = 0
        val seenKeys = HashSet<String>()
        val iterator = options.entries.iterator()
        while (iterator.hasNext()) {
            if (count == limits.maxOptions) {
                rejectBudget(path, QueryRejectionCode.OPTIONS_LIMIT_EXCEEDED)
            }
            val entry = iterator.next()
            count++
            val key = entry.key
            if (key !is String) {
                rejectInvalid(path, QueryRejectionCode.INVALID_OPTION_TYPE)
            }
            val optionPath = path.key(key)
            session.budget.consumeString(key, optionPath)
            if (!seenKeys.add(key)) {
                rejectInvalid(optionPath, QueryRejectionCode.DUPLICATE_OBJECT_KEY)
            }
            val value = entry.value
            session.budget.enterValue(optionPath)
            when (key) {
                Condition.IGNORE_CASE_OPTION_KEY -> {
                    ensureOptionAllowed(operator, STRING_OPTION_OPERATORS, optionPath)
                    if (value !is Boolean) {
                        rejectInvalid(optionPath, QueryRejectionCode.INVALID_OPTION_TYPE)
                    }
                    caseSensitivity = if (value) CaseSensitivity.INSENSITIVE else CaseSensitivity.SENSITIVE
                }

                Condition.ZONE_ID_OPTION_KEY -> {
                    ensureOptionAllowed(operator, TIME_OPERATORS, optionPath)
                    zoneId = normalizeZoneId(value, optionPath, session)
                }

                Condition.DATE_PATTERN_OPTION_KEY -> {
                    ensureOptionAllowed(operator, TIME_OPERATORS, optionPath)
                    datePattern = normalizeDatePattern(value, optionPath, session)
                }

                else -> rejectInvalid(optionPath, QueryRejectionCode.UNKNOWN_OPTION)
            }
        }
        return AdmittedConditionOptions(caseSensitivity, zoneId, datePattern)
    }

    private fun ensureOptionAllowed(
        operator: Operator,
        allowed: Set<Operator>,
        path: QueryRejectionPath,
    ) {
        if (operator !in allowed) {
            rejectInvalid(path, QueryRejectionCode.OPTION_NOT_ALLOWED)
        }
    }

    private fun normalizeZoneId(
        value: Any?,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): ZoneId =
        when (value) {
            is ZoneId -> value
            is String -> {
                session.budget.consumeString(value, path)
                try {
                    ZoneId.of(value)
                } catch (error: DateTimeException) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_OPTION_VALUE, error)
                }
            }
            else -> rejectInvalid(path, QueryRejectionCode.INVALID_OPTION_TYPE)
        }

    private fun normalizeDatePattern(
        value: Any?,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedDatePattern =
        when (value) {
            is DateTimeFormatter -> admitDateTimeFormatter(value, path, session)
            is String -> {
                session.budget.consumeString(value, path)
                try {
                    val formatter = DateTimeFormatter.ofPattern(value)
                    AdmittedDatePattern(formatter, formatter.toString())
                } catch (error: IllegalArgumentException) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_OPTION_VALUE, error)
                }
            }
            else -> rejectInvalid(path, QueryRejectionCode.INVALID_OPTION_TYPE)
        }

    private fun admitDateTimeFormatter(
        formatter: DateTimeFormatter,
        path: QueryRejectionPath,
        session: AdmissionSession,
    ): AdmittedDatePattern {
        val descriptor = formatter.toString()
        session.budget.consumeString(descriptor, path)
        return AdmittedDatePattern(formatter, descriptor)
    }

    private fun validateConditionField(field: String, operator: Operator, path: QueryRejectionPath) {
        if (operator in FIELDLESS_OPERATORS) {
            if (field.isNotEmpty()) {
                rejectInvalid(path, QueryRejectionCode.INVALID_FIELD)
            }
            return
        }
        validateField(field, path)
    }

    private fun validateField(field: String, path: QueryRejectionPath) {
        if (field.isBlank()) {
            rejectInvalid(path, QueryRejectionCode.FIELD_REQUIRED)
        }
        if (field.length > limits.maxFieldLength) {
            rejectBudget(path, QueryRejectionCode.STRING_LIMIT_EXCEEDED)
        }
        if (field.split('.').any { it.isBlank() || it.any(Char::isISOControl) }) {
            rejectInvalid(path, QueryRejectionCode.INVALID_FIELD)
        }
    }

    private fun rejectInvalid(
        path: QueryRejectionPath,
        code: QueryRejectionCode,
        cause: Throwable? = null,
    ): Nothing = rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, code, cause)

    private fun rejectBudget(path: QueryRejectionPath, code: QueryRejectionCode): Nothing =
        rejectQuery(QueryRejectionCategory.BUDGET_EXCEEDED, path, code)

    private class AdmissionSession(limits: QueryAdmissionLimits) {
        var conditionNodes: Int = 0
        val conditionActive: IdentityHashMap<Condition, Unit> = IdentityHashMap()
        val budget: AdmissionBudget = AdmissionBudget(limits)
    }

    companion object {
        private val FIELDLESS_OPERATORS = setOf(
            Operator.AND,
            Operator.OR,
            Operator.NOR,
            Operator.ID,
            Operator.IDS,
            Operator.AGGREGATE_ID,
            Operator.AGGREGATE_IDS,
            Operator.TENANT_ID,
            Operator.OWNER_ID,
            Operator.SPACE_ID,
            Operator.DELETED,
            Operator.ALL,
            Operator.RAW,
        )
        private val STRING_OPTION_OPERATORS = setOf(
            Operator.CONTAINS,
            Operator.STARTS_WITH,
            Operator.ENDS_WITH,
        )
        private val TIME_OPERATORS = setOf(
            Operator.TODAY,
            Operator.BEFORE_TODAY,
            Operator.TOMORROW,
            Operator.THIS_WEEK,
            Operator.NEXT_WEEK,
            Operator.LAST_WEEK,
            Operator.THIS_MONTH,
            Operator.LAST_MONTH,
            Operator.RECENT_DAYS,
            Operator.EARLIER_DAYS,
        )
    }
}
