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

import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.QueryRequestException

/**
 * The error catalog (design §5.9): every rule a query can break, as structured facts. Each violation names its
 * stable [code], renders its [message] from its facts, and belongs to a [Kind] that decides the error it becomes
 * ([rejection]): the one place a rejection is rendered. Codes are a contract (added, never renamed); texts are not.
 *
 * Server faults (a failing mask strategy, a result that breaks integrity, a storage that timed out) are not
 * violations: they are [me.ahoo.wow.query.QueryExecutionException]s.
 */
sealed interface QueryViolation {
    val message: String

    /** A stable, machine-readable code; a contract: codes are added, never renamed. */
    val code: String

    /** The logical field the violation is about, or `null` for model-level violations. */
    val field: QueryField?
        get() = null

    /** What the binding error names: the absolute logical field (or `""`) for a model rule, a request part otherwise. */
    val location: String
        get() = this.field?.path.orEmpty()

    val kind: Kind
        get() = Kind.MODEL

    /** Which error a violation renders as. */
    enum class Kind {
        /** The request asks for more than its entry allows, or is malformed: `IllegalArgument`, HTTP 400. */
        REQUEST,

        /** The request does not fit the query model or its storage: `QuerySchemaValidation`, HTTP 400. */
        MODEL,
    }

    /** The error this violation is rejected with; the one place a violation is rendered. */
    fun rejection(): RuntimeException = when (kind) {
        Kind.REQUEST -> QueryRequestException(this)
        Kind.MODEL -> QuerySchemaValidationException(this)
    }

    data class UnknownField(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.UNKNOWN_FIELD

        override val message: String
            get() = "Unknown logical field [${this.field}]."
    }

    /** The field grants none of [capabilities]. */
    data class UnsupportedCapability(
        override val field: QueryField,
        val capabilities: Set<QueryCapability>
    ) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.UNSUPPORTED_CAPABILITY

        override val message: String
            get() = "Field [${this.field}] does not support [${capabilities.joinToString(" or ")}]."
    }

    data class ElementScopeRequired(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.ELEMENT_SCOPE_REQUIRED

        override val message: String
            get() = "Field [${this.field}] requires its declared element scope."
    }

    /** A filter value falls outside the field's declared domain; [field] is the name as the request wrote it. */
    data class ValueMismatch(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.VALUE_MISMATCH

        override val message: String
            get() = "Filter value does not match [${this.field}]."
    }

    data class NotCollection(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.NOT_COLLECTION

        override val message: String
            get() = "Field [${this.field}] is not a known collection."
    }

    data class NotSingleString(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.NOT_SINGLE_STRING

        override val message: String
            get() = "Field [${this.field}] is not a single string."
    }

    data object ModelSearchUnsupported : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.MODEL_SEARCH_UNSUPPORTED

        override val message: String
            get() = "Model search is unsupported."
    }

    data class CursorNotAllowed(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.CURSOR_NOT_ALLOWED

        override val message: String
            get() = "Field [${this.field}] cannot be used for a cursor."
    }

    data class ProtectedAggregation(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.PROTECTED_AGGREGATION

        override val message: String
            get() = "Protected field [${this.field}] cannot be aggregated."
    }

    /** A filter or sort compares a sensitive field whose raw value must not be compared. */
    data class ProtectedComparison(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.PROTECTED_COMPARISON

        override val message: String
            get() = "Protected field [${this.field}] cannot be filtered or sorted."
    }

    data class MissingKeyRequiresString(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.MISSING_KEY_REQUIRES_STRING

        override val message: String
            get() = "Field [${this.field}] must be a single-valued string field to declare missingKey."
    }

    data object AnyRequiresSingleValue : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.ANY_REQUIRES_SINGLE_VALUE

        override val message: String
            get() = "ANY requires a single value."
    }

    data object IncompleteProjection : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.INCOMPLETE_PROJECTION

        override val message: String
            get() = "Native storage cannot deliver a complete source projection; select available fields explicitly."
    }

    data object MetricFilterSearch : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.METRIC_FILTER_SEARCH

        override val message: String
            get() = "Aggregation metric filters do not support search filters."
    }

    data object MetricFilterElementMatch : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.METRIC_FILTER_ELEMENT_MATCH

        override val message: String
            get() = "Aggregation metric filters do not support [ELEMENT_MATCH]."
    }

    data class MetricFilterArrayField(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.METRIC_FILTER_ARRAY_FIELD

        override val message: String
            get() = "Aggregation metric filter field [${this.field}] must be scalar; array fields are not supported in " +
                "metric filters."
    }

    data class NotProjectable(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.NOT_PROJECTABLE

        override val message: String
            get() = "Field [${this.field}] cannot be projected."
    }

    /** An event projection that keeps payloads must keep their type, so each payload can be read. */
    data class EventProjectionTypeRequired(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.EVENT_PROJECTION_TYPE_REQUIRED

        override val message: String
            get() = "Event payload projection must retain bodyType."
    }

    data class TemporalRepresentationRequired(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.TEMPORAL_REPRESENTATION_REQUIRED

        override val message: String
            get() = "Relative-time field requires a known temporal representation."
    }

    data class TemporalConfigurationConflict(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.TEMPORAL_CONFIGURATION_CONFLICT

        override val message: String
            get() = "Relative-time configuration conflicts with its value definition."
    }

    /**
     * A date group or date difference names a field whose values do not store instants as a date or an epoch, so
     * no backend can read them as instants.
     */
    data class TemporalAggregationUnsupported(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.TEMPORAL_AGGREGATION_UNSUPPORTED

        override val message: String
            get() = "Field [${this.field}] must store a date or an epoch to be aggregated by time."
    }

    /** FIRST / LAST need a single value per record, both for the value and for its ordering field. */
    data class FirstLastRequiresSingleValue(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.FIRST_LAST_REQUIRES_SINGLE_VALUE

        override val message: String
            get() = "FIRST and LAST require a single-valued field, but [${this.field}] may hold several values."
    }

    /** A FIRST / LAST metric inside an element, or on a model without an event time, names no `orderBy`. */
    data class FirstLastRequiresOrderBy(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.FIRST_LAST_REQUIRES_ORDER_BY

        override val message: String
            get() = "Metric [${this.field}] must name orderBy: there is no event time to order by in its scope."
    }

    /** [field] and [other] are independent arrays, and the storage cannot sort by both. */
    data class ParallelArraySort(override val field: QueryField, val other: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.PARALLEL_ARRAY_SORT

        override val message: String
            get() = "Sort fields [${this.other}] and [${this.field}] are independent arrays; the storage cannot sort by both."
    }

    /** `EQ` / `NE` on [field] names an array operand, and the storage cannot compare a whole array. */
    data class ArrayEquality(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.ARRAY_EQUALITY

        override val message: String
            get() = "Field [${this.field}] cannot be compared to an array; the storage supports only scalar operands."
    }

    /** A sort names more fields than any storage supports. */
    data class SortTooMany(val max: Int) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.SORT_TOO_MANY

        override val message: String
            get() = "Sort must contain at most $max fields."
    }

    /** Two sort fields ([field] is the later one) are bound to the same physical field, so one of them sorts nothing. */
    data class DuplicateSortField(override val field: QueryField) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.SORT_FIELD_DUPLICATE

        override val message: String
            get() = "Sort field [${this.field}] maps to the same physical field as an earlier sort field."
    }

    /** An identity filter or a cursor (whose tie-breaker is the identity) on a [model] that defines no identity. */
    data class IdentityUndefined(val model: QueryModel) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.IDENTITY_UNDEFINED

        override val message: String
            get() = "Record identity is not defined for model [${model.value}]."
    }

    /**
     * The storage cannot run [feature]: it declares the feature unsupported, or its native language cannot express
     * the query (a backend's native check). [field] is the field involved, when there is one.
     */
    data class StorageUnsupported(val feature: String, override val field: QueryField? = null) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.STORAGE_UNSUPPORTED

        override val message: String
            get() = "Storage does not support $feature."
    }

    /*
     * Request violations: the entry budget and gates of step 0 (QueryBudget, QueryEntryPolicy) and the cursor token.
     * [label] names the entry and opens the text, e.g. `HTTP page size[0] ...`.
     */

    /**
     * A size the entry bounds ([subject]: a list or aggregation limit, a page index, size, window or offset, a cursor
     * size) is [value], outside [min]..[max]; a `null` bound is open.
     */
    data class SizeOutOfRange(
        val label: String,
        val subject: String,
        val value: Long,
        val min: Long?,
        val max: Long?,
        override val location: String,
    ) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val code: String
            get() = QueryErrorCodes.SIZE_OUT_OF_RANGE

        override val message: String
            get() = "$label $subject[$value] " + when {
                min != null && max != null -> "must be between $min and $max."
                min != null -> "must be greater than or equal to $min."
                else -> "must not exceed $max."
            }
    }

    /** The filter (and HAVING) of a query has more nodes or values ([subject]) than the entry allows. */
    data class FilterTooLarge(
        val label: String,
        val subject: String,
        val value: Int,
        val max: Int,
        override val location: String,
    ) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val code: String
            get() = QueryErrorCodes.FILTER_TOO_LARGE

        override val message: String
            get() = "$label $subject[$value] must not exceed $max."
    }

    /** [construct] (e.g. `query operator[CONTAINS]`) is expensive, and the entry does not allow expensive operators. */
    data class ExpensiveOperatorDisabled(
        val label: String,
        val construct: String,
        val plural: Boolean = false,
        override val location: String,
    ) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val code: String
            get() = QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED

        override val message: String
            get() = "$label $construct ${if (plural) "are" else "is"} disabled because expensive operators are not " +
                "allowed."
    }

    /** A counting query (count, paged total) matches every record, and the entry does not allow expensive operators. */
    data class CountRequiresFilter(val label: String) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = FILTER

        override val code: String
            get() = QueryErrorCodes.COUNT_REQUIRES_FILTER

        override val message: String
            get() = "$label counting query must not match all documents."
    }

    /**
     * An aggregation whose HAVING or metric sort the core computes read more than [max] groups, dense fill rows
     * included. Metered while the rows arrive, so it can fail after the response started streaming.
     */
    data class ResidualGroupsExceeded(val label: String, val max: Int) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = QueryRequestException.BODY

        override val code: String
            get() = QueryErrorCodes.RESIDUAL_GROUPS_EXCEEDED

        override val message: String
            get() = "$label aggregation processes more than [$max] groups, dense fill included, to compute HAVING " +
                "or a metric sort in the query service; narrow the filter or the period."
    }

    /** The gateway requires every query to state its entry, and this one ran under `UNSPECIFIED`. */
    data object ExplicitEntryRequired : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = QueryRequestException.BODY

        override val code: String
            get() = QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED

        override val message: String
            get() = "Query entry must be explicit: run the query under QueryEntry.HTTP or QueryEntry.IN_PROCESS."
    }

    /** A cursor token that does not decode for this model and effective sort. */
    data object InvalidCursor : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = "cursor"

        override val code: String
            get() = QueryErrorCodes.INVALID_CURSOR

        override val message: String
            get() = "Invalid cursor."
    }

    /** A cursor sort names [field] twice once the identity tie-breaker is appended. */
    data class CursorSortDuplicate(override val field: QueryField) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val code: String
            get() = QueryErrorCodes.CURSOR_SORT_DUPLICATE

        override val message: String
            get() = "Cursor sort fields must be unique."
    }

    /** A cursor sort has more than [max] fields once the identity tie-breaker is appended. */
    data class CursorSortTooMany(val max: Int) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = "sort"

        override val code: String
            get() = QueryErrorCodes.CURSOR_SORT_TOO_MANY

        override val message: String
            get() = "Effective cursor sort must contain at most $max fields."
    }

    companion object {
        /** The request part a filter-wide rule names. */
        const val FILTER: String = "filter"
    }
}

internal inline fun requireValid(accepted: Boolean, violation: () -> QueryViolation) {
    if (!accepted) throw violation().rejection()
}
