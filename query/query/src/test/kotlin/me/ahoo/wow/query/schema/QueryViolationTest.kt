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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.error.QueryBindingError
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryRequestException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** The error catalog (design §5.9), entry by entry: its stable code, its text, its kind and where it points. */
class QueryViolationTest {
    private val field = QueryField("state.name")
    private val other = QueryField("state.tags")

    private data class Row(val code: String, val message: String, val location: String = "state.name")

    private val model: Map<QueryViolation, Row> = mapOf(
        QueryViolation.UnknownField(field) to
            Row(QueryErrorCodes.UNKNOWN_FIELD, "Unknown logical field [state.name]."),
        QueryViolation.UnsupportedCapability(field, setOf(QueryCapability.SORT, QueryCapability.RANGE)) to
            Row(QueryErrorCodes.UNSUPPORTED_CAPABILITY, "Field [state.name] does not support [SORT or RANGE]."),
        QueryViolation.ElementScopeRequired(field) to Row(
            QueryErrorCodes.ELEMENT_SCOPE_REQUIRED,
            "Field [state.name] requires its declared element scope: reach it through ELEMENT_MATCH in a filter, " +
                "or expand it with the aggregation's elements.",
        ),
        QueryViolation.ValueMismatch(field) to
            Row(QueryErrorCodes.VALUE_MISMATCH, "Filter value does not match [state.name]."),
        QueryViolation.NotCollection(field) to
            Row(QueryErrorCodes.NOT_COLLECTION, "Field [state.name] is not a known collection."),
        QueryViolation.NotSingleString(field) to
            Row(QueryErrorCodes.NOT_SINGLE_STRING, "Field [state.name] is not a single string."),
        QueryViolation.ModelSearchUnsupported to
            Row(QueryErrorCodes.MODEL_SEARCH_UNSUPPORTED, "Model search is unsupported.", ""),
        QueryViolation.CursorNotAllowed(field) to
            Row(QueryErrorCodes.CURSOR_NOT_ALLOWED, "Field [state.name] cannot be used for a cursor."),
        QueryViolation.ProtectedAggregation(field) to
            Row(QueryErrorCodes.PROTECTED_AGGREGATION, "Protected field [state.name] cannot be aggregated."),
        QueryViolation.ProtectedComparison(field) to
            Row(QueryErrorCodes.PROTECTED_COMPARISON, "Protected field [state.name] cannot be filtered or sorted."),
        QueryViolation.MissingKeyRequiresString(field) to Row(
            QueryErrorCodes.MISSING_KEY_REQUIRES_STRING,
            "Field [state.name] must be a single-valued string field to declare missingKey.",
        ),
        QueryViolation.AnyRequiresSingleValue to
            Row(QueryErrorCodes.ANY_REQUIRES_SINGLE_VALUE, "ANY requires a single value.", ""),
        QueryViolation.IncompleteProjection to Row(
            QueryErrorCodes.INCOMPLETE_PROJECTION,
            "Native storage cannot deliver a complete source projection; select available fields explicitly.",
            "",
        ),
        QueryViolation.MetricFilterSearch to Row(
            QueryErrorCodes.METRIC_FILTER_SEARCH,
            "Aggregation metric filters do not support search filters.",
            "",
        ),
        QueryViolation.MetricFilterElementMatch to Row(
            QueryErrorCodes.METRIC_FILTER_ELEMENT_MATCH,
            "Aggregation metric filters do not support [ELEMENT_MATCH].",
            "",
        ),
        QueryViolation.MetricFilterArrayField(field) to Row(
            QueryErrorCodes.METRIC_FILTER_ARRAY_FIELD,
            "Aggregation metric filter field [state.name] must be scalar; array fields are not supported in " +
                "metric filters.",
        ),
        QueryViolation.NotProjectable(field) to
            Row(QueryErrorCodes.NOT_PROJECTABLE, "Field [state.name] cannot be projected."),
        QueryViolation.EventProjectionTypeRequired(field) to
            Row(QueryErrorCodes.EVENT_PROJECTION_TYPE_REQUIRED, "Event payload projection must retain bodyType."),
        QueryViolation.TemporalRepresentationRequired(field) to Row(
            QueryErrorCodes.TEMPORAL_REPRESENTATION_REQUIRED,
            "Relative-time field requires a known temporal representation.",
        ),
        QueryViolation.TemporalConfigurationConflict(field) to Row(
            QueryErrorCodes.TEMPORAL_CONFIGURATION_CONFLICT,
            "Relative-time configuration conflicts with its value definition.",
        ),
        QueryViolation.TemporalAggregationUnsupported(field) to Row(
            QueryErrorCodes.TEMPORAL_AGGREGATION_UNSUPPORTED,
            "Field [state.name] must store a date or an epoch to be aggregated by time.",
        ),
        QueryViolation.FirstLastRequiresSingleValue(field) to Row(
            QueryErrorCodes.FIRST_LAST_REQUIRES_SINGLE_VALUE,
            "FIRST and LAST require a single-valued field, but [state.name] may hold several values.",
        ),
        QueryViolation.FirstLastRequiresOrderBy(field) to Row(
            QueryErrorCodes.FIRST_LAST_REQUIRES_ORDER_BY,
            "Metric [state.name] must name orderBy: there is no event time to order by in its scope.",
        ),
        QueryViolation.ParallelArraySort(field, other) to Row(
            QueryErrorCodes.PARALLEL_ARRAY_SORT,
            "Sort fields [state.tags] and [state.name] are independent arrays; the storage cannot sort by both.",
        ),
        QueryViolation.ArrayEquality(field) to Row(
            QueryErrorCodes.ARRAY_EQUALITY,
            "Field [state.name] cannot be compared to an array; the storage supports only scalar operands.",
        ),
        QueryViolation.SortTooMany(3) to
            Row(QueryErrorCodes.SORT_TOO_MANY, "Sort must contain at most 3 fields.", ""),
        QueryViolation.DuplicateSortField(field) to Row(
            QueryErrorCodes.SORT_FIELD_DUPLICATE,
            "Sort field [state.name] maps to the same physical field as an earlier sort field.",
        ),
        QueryViolation.IdentityUndefined(QueryModel("CUSTOM")) to Row(
            QueryErrorCodes.IDENTITY_UNDEFINED,
            "Record identity is not defined for model [CUSTOM].",
            "",
        ),
        QueryViolation.StorageUnsupported("HAVING") to
            Row(QueryErrorCodes.STORAGE_UNSUPPORTED, "Storage does not support HAVING.", ""),
        QueryViolation.StorageUnsupported("PERCENTILE", field) to
            Row(QueryErrorCodes.STORAGE_UNSUPPORTED, "Storage does not support PERCENTILE."),
        QueryViolation.PreviousUnshiftable(field) to Row(
            QueryErrorCodes.PREVIOUS_UNSHIFTABLE,
            "PREVIOUS reads the earlier period by shifting the filter's range of [state.name], so the filter must " +
                "condition the field only in its top-level AND, with instant values.",
        ),
    )

    private val request: Map<QueryViolation, Row> = mapOf(
        QueryViolation.SizeOutOfRange("HTTP", "page size", 0, 1, 100, "pagination.size") to Row(
            QueryErrorCodes.SIZE_OUT_OF_RANGE,
            "HTTP page size[0] must be between 1 and 100.",
            "pagination.size",
        ),
        QueryViolation.SizeOutOfRange("HTTP", "page index", 0, 1, null, "pagination.index") to Row(
            QueryErrorCodes.SIZE_OUT_OF_RANGE,
            "HTTP page index[0] must be greater than or equal to 1.",
            "pagination.index",
        ),
        QueryViolation.SizeOutOfRange("HTTP", "list limit", 500, null, 100, "limit") to Row(
            QueryErrorCodes.SIZE_OUT_OF_RANGE,
            "HTTP list limit[500] must not exceed 100.",
            "limit",
        ),
        QueryViolation.FilterTooLarge("HTTP", "filter nodes", 300, 256, "filter") to
            Row(QueryErrorCodes.FILTER_TOO_LARGE, "HTTP filter nodes[300] must not exceed 256.", "filter"),
        QueryViolation.ExpensiveOperatorDisabled("HTTP", "query operator[CONTAINS]", location = "filter") to Row(
            QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED,
            "HTTP query operator[CONTAINS] is disabled because expensive operators are not allowed.",
            "filter",
        ),
        QueryViolation.ExpensiveOperatorDisabled("HTTP", "date parts", plural = true, location = "groupBy") to Row(
            QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED,
            "HTTP date parts are disabled because expensive operators are not allowed.",
            "groupBy",
        ),
        QueryViolation.ExactCountDisabled("HTTP") to Row(
            QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED,
            "HTTP exact total is disabled: ask for a BOUNDED total, or count with a limit.",
            "total",
        ),
        QueryViolation.CountRequiresFilter("HTTP") to Row(
            QueryErrorCodes.COUNT_REQUIRES_FILTER,
            "HTTP counting query must not match all documents.",
            QueryViolation.FILTER,
        ),
        QueryViolation.ResidualGroupsExceeded("HTTP", 1000) to Row(
            QueryErrorCodes.RESIDUAL_GROUPS_EXCEEDED,
            "HTTP aggregation processes more than [1000] groups, dense fill included, to compute HAVING, a metric " +
                "sort or a window in the query service; narrow the filter or the period.",
            QueryRequestException.BODY,
        ),
        QueryViolation.ExplicitEntryRequired to Row(
            QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED,
            "Query entry must be explicit: run the query under QueryEntry.HTTP or QueryEntry.IN_PROCESS.",
            QueryRequestException.BODY,
        ),
        QueryViolation.InvalidCursor to Row(QueryErrorCodes.INVALID_CURSOR, "Invalid cursor.", "cursor"),
        QueryViolation.CursorSortDuplicate(field) to
            Row(QueryErrorCodes.CURSOR_SORT_DUPLICATE, "Cursor sort fields must be unique."),
        QueryViolation.CursorSortTooMany(4) to Row(
            QueryErrorCodes.CURSOR_SORT_TOO_MANY,
            "Effective cursor sort must contain at most 4 fields.",
            "sort",
        ),
    )

    @Test
    fun `every model violation renders its code, text and field as a schema validation rejection`() {
        model.forEach { (violation, row) ->
            Row(violation.code, violation.message, violation.location).assert().describedAs(violation.toString())
                .isEqualTo(row)
            violation.kind.assert().isEqualTo(QueryViolation.Kind.MODEL)
            val rejection = violation.rejection() as QuerySchemaValidationException
            rejection.violation.assert().isSameAs(violation)
            rejection.errorCode.assert().isEqualTo(QueryErrors.INVALID)
            rejection.bindingErrors.assert().containsExactly(QueryBindingError(row.location, row.message, row.code))
        }
    }

    @Test
    fun `every request violation renders its code, text and request part as a request rejection`() {
        request.forEach { (violation, row) ->
            Row(violation.code, violation.message, violation.location).assert().describedAs(violation.toString())
                .isEqualTo(row)
            violation.kind.assert().isEqualTo(QueryViolation.Kind.REQUEST)
            val rejection = violation.rejection() as QueryRequestException
            rejection.violation.assert().isSameAs(violation)
            rejection.errorCode.assert().isEqualTo(QueryErrors.INVALID)
            rejection.bindingErrors.assert().containsExactly(QueryBindingError(row.location, row.message, row.code))
        }
    }

    @Test
    fun `a violation names its field only when it is about one`() {
        QueryViolation.UnknownField(field).field.assert().isEqualTo(field)
        QueryViolation.ModelSearchUnsupported.field.assert().isNull()
        QueryViolation.SortTooMany(3).field.assert().isNull()
        QueryViolation.ParallelArraySort(field, other).other.assert().isEqualTo(other)
    }

    @Test
    fun `requireValid throws the rejection of the violation only when the rule is broken`() {
        assertDoesNotThrow { requireValid(true) { QueryViolation.InvalidCursor } }
        assertThrows<QueryRequestException> { requireValid(false) { QueryViolation.InvalidCursor } }
        assertThrows<QuerySchemaValidationException> { requireValid(false) { QueryViolation.UnknownField(field) } }
    }
}
