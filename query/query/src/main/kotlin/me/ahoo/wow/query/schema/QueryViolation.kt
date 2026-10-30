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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryRequestException

/**
 * The error catalog: every rule a query can break, as structured facts. Each violation names its
 * stable [code], renders its [message] from its facts, and belongs to a [Kind] that decides the error it becomes
 * ([rejection]): the one place a rejection is rendered. Codes are a contract (added, never renamed); texts are not.
 *
 * Both kinds are answered as `wow.query.invalid`, each violation carrying its code. Server faults (a
 * result that breaks integrity, a storage that failed or timed out) are not violations: they are
 * [me.ahoo.wow.query.error.QueryExecutionException]s.
 */
sealed interface QueryViolation {
    val message: String

    /** A stable, machine-readable code; a contract: codes are added, never renamed. */
    val code: String

    /** The logical field the violation is about, or `null` for model-level violations. */
    val field: QueryField<*>?
        get() = null

    /** What the binding error names: the absolute logical field (or `""`) for a model rule, a request part otherwise. */
    val location: String
        get() = this.field?.path.orEmpty()

    val kind: Kind
        get() = Kind.MODEL

    /** Which error a violation renders as. */
    enum class Kind {
        /** The request asks for more than its entry allows: a [QueryRequestException]. */
        REQUEST,

        /** The request does not fit the query model or its storage: a [QuerySchemaValidationException]. */
        MODEL,
    }

    /** The error this violation is rejected with; the one place a violation is rendered. */
    fun rejection(): RuntimeException = when (kind) {
        Kind.REQUEST -> QueryRequestException(this)
        Kind.MODEL -> QuerySchemaValidationException(this)
    }

    class UnknownField @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.UNKNOWN_FIELD

        override val message: String
            get() = "Unknown logical field [${this.field}]."

        override fun equals(other: Any?): Boolean =
            this === other || other is UnknownField && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "UnknownField(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [UnknownField] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): UnknownField = UnknownField(field)
        }
    }

    /** The field grants none of [capabilities]. */
    class UnsupportedCapability @InternalWowApi constructor(
        override val field: QueryField<*>,
        val capabilities: Set<QueryCapability>
    ) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.UNSUPPORTED_CAPABILITY

        override val message: String
            get() = "Field [${this.field}] does not support [${capabilities.joinToString(" or ")}]."

        override fun equals(other: Any?): Boolean =
            this === other || other is UnsupportedCapability && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "UnsupportedCapability(field=$field, capabilities=$capabilities)"

        private fun valueFields(): List<Any?> = listOf(field, capabilities)

        companion object {
            /** A [UnsupportedCapability] of the given values. */
            @JvmStatic
            fun of(
                field: QueryField<*>,
                capabilities: Set<QueryCapability>,
            ): UnsupportedCapability = UnsupportedCapability(field, capabilities)
        }
    }

    class ElementScopeRequired @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.ELEMENT_SCOPE_REQUIRED

        override val message: String
            get() =
                "Field [${this.field}] requires its declared element scope: reach it through ELEMENT_MATCH in a " +
                    "filter, or expand it with the aggregation's elements."

        override fun equals(other: Any?): Boolean =
            this === other || other is ElementScopeRequired && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ElementScopeRequired(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [ElementScopeRequired] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): ElementScopeRequired = ElementScopeRequired(field)
        }
    }

    /** A filter value falls outside the field's declared domain; [field] is the name as the request wrote it. */
    class ValueMismatch @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.VALUE_MISMATCH

        override val message: String
            get() = "Filter value does not match [${this.field}]."

        override fun equals(other: Any?): Boolean =
            this === other || other is ValueMismatch && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ValueMismatch(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [ValueMismatch] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): ValueMismatch = ValueMismatch(field)
        }
    }

    class NotCollection @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.NOT_COLLECTION

        override val message: String
            get() = "Field [${this.field}] is not a known collection."

        override fun equals(other: Any?): Boolean =
            this === other || other is NotCollection && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "NotCollection(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [NotCollection] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): NotCollection = NotCollection(field)
        }
    }

    class NotSingleString @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.NOT_SINGLE_STRING

        override val message: String
            get() = "Field [${this.field}] is not a single string."

        override fun equals(other: Any?): Boolean =
            this === other || other is NotSingleString && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "NotSingleString(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [NotSingleString] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): NotSingleString = NotSingleString(field)
        }
    }

    data object ModelSearchUnsupported : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.MODEL_SEARCH_UNSUPPORTED

        override val message: String
            get() = "Model search is unsupported."
    }

    class CursorNotAllowed @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.CURSOR_NOT_ALLOWED

        override val message: String
            get() = "Field [${this.field}] cannot be used for a cursor."

        override fun equals(other: Any?): Boolean =
            this === other || other is CursorNotAllowed && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "CursorNotAllowed(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [CursorNotAllowed] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): CursorNotAllowed = CursorNotAllowed(field)
        }
    }

    class ProtectedAggregation @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.PROTECTED_AGGREGATION

        override val message: String
            get() = "Protected field [${this.field}] cannot be aggregated."

        override fun equals(other: Any?): Boolean =
            this === other || other is ProtectedAggregation && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ProtectedAggregation(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [ProtectedAggregation] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): ProtectedAggregation = ProtectedAggregation(field)
        }
    }

    /** A filter or sort compares a sensitive field whose raw value must not be compared. */
    class ProtectedComparison @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.PROTECTED_COMPARISON

        override val message: String
            get() = "Protected field [${this.field}] cannot be filtered or sorted."

        override fun equals(other: Any?): Boolean =
            this === other || other is ProtectedComparison && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ProtectedComparison(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [ProtectedComparison] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): ProtectedComparison = ProtectedComparison(field)
        }
    }

    class MissingKeyRequiresString @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.MISSING_KEY_REQUIRES_STRING

        override val message: String
            get() = "Field [${this.field}] must be a single-valued string field to declare missingKey."

        override fun equals(other: Any?): Boolean =
            this === other || other is MissingKeyRequiresString && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "MissingKeyRequiresString(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [MissingKeyRequiresString] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): MissingKeyRequiresString = MissingKeyRequiresString(field)
        }
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

    class MetricFilterArrayField @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.METRIC_FILTER_ARRAY_FIELD

        override val message: String
            get() = "Aggregation metric filter field [${this.field}] must be scalar; array fields are not supported in " +
                "metric filters."

        override fun equals(other: Any?): Boolean =
            this === other || other is MetricFilterArrayField && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "MetricFilterArrayField(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [MetricFilterArrayField] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): MetricFilterArrayField = MetricFilterArrayField(field)
        }
    }

    class NotProjectable @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.NOT_PROJECTABLE

        override val message: String
            get() = "Field [${this.field}] cannot be projected."

        override fun equals(other: Any?): Boolean =
            this === other || other is NotProjectable && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "NotProjectable(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [NotProjectable] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): NotProjectable = NotProjectable(field)
        }
    }

    /** An event projection that keeps payloads must keep their type, so each payload can be read. */
    class EventProjectionTypeRequired @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.EVENT_PROJECTION_TYPE_REQUIRED

        override val message: String
            get() = "Event payload projection must retain bodyType."

        override fun equals(other: Any?): Boolean =
            this === other || other is EventProjectionTypeRequired && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "EventProjectionTypeRequired(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [EventProjectionTypeRequired] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): EventProjectionTypeRequired = EventProjectionTypeRequired(field)
        }
    }

    class TemporalRepresentationRequired @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.TEMPORAL_REPRESENTATION_REQUIRED

        override val message: String
            get() = "Relative-time field requires a known temporal representation."

        override fun equals(other: Any?): Boolean =
            this === other || other is TemporalRepresentationRequired && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "TemporalRepresentationRequired(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [TemporalRepresentationRequired] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): TemporalRepresentationRequired = TemporalRepresentationRequired(field)
        }
    }

    class TemporalConfigurationConflict @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.TEMPORAL_CONFIGURATION_CONFLICT

        override val message: String
            get() = "Relative-time configuration conflicts with its value definition."

        override fun equals(other: Any?): Boolean =
            this === other || other is TemporalConfigurationConflict && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "TemporalConfigurationConflict(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [TemporalConfigurationConflict] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): TemporalConfigurationConflict = TemporalConfigurationConflict(field)
        }
    }

    /**
     * A date group or date difference names a field whose values do not store instants as a date or an epoch, so
     * no backend can read them as instants.
     */
    class TemporalAggregationUnsupported @InternalWowApi constructor(
        override val field: QueryField<*>
    ) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.TEMPORAL_AGGREGATION_UNSUPPORTED

        override val message: String
            get() = "Field [${this.field}] must store a date or an epoch to be aggregated by time."

        override fun equals(other: Any?): Boolean =
            this === other || other is TemporalAggregationUnsupported && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "TemporalAggregationUnsupported(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [TemporalAggregationUnsupported] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): TemporalAggregationUnsupported = TemporalAggregationUnsupported(field)
        }
    }

    /** FIRST / LAST need a single value per record, both for the value and for its ordering field. */
    class FirstLastRequiresSingleValue @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.FIRST_LAST_REQUIRES_SINGLE_VALUE

        override val message: String
            get() = "FIRST and LAST require a single-valued field, but [${this.field}] may hold several values."

        override fun equals(other: Any?): Boolean =
            this === other || other is FirstLastRequiresSingleValue && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "FirstLastRequiresSingleValue(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [FirstLastRequiresSingleValue] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): FirstLastRequiresSingleValue = FirstLastRequiresSingleValue(field)
        }
    }

    /** A FIRST / LAST metric inside an element, or on a model without an event time, names no `orderBy`. */
    class FirstLastRequiresOrderBy @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.FIRST_LAST_REQUIRES_ORDER_BY

        override val message: String
            get() = "Metric [${this.field}] must name orderBy: there is no event time to order by in its scope."

        override fun equals(other: Any?): Boolean =
            this === other || other is FirstLastRequiresOrderBy && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "FirstLastRequiresOrderBy(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [FirstLastRequiresOrderBy] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): FirstLastRequiresOrderBy = FirstLastRequiresOrderBy(field)
        }
    }

    /** [field] and [other] are independent arrays, and the storage cannot sort by both. */
    class ParallelArraySort @InternalWowApi constructor(
        override val field: QueryField<*>,
        val other: QueryField<*>
    ) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.PARALLEL_ARRAY_SORT

        override val message: String
            get() = "Sort fields [${this.other}] and [${this.field}] are independent arrays; the storage cannot sort by both."

        override fun equals(other: Any?): Boolean =
            this === other || other is ParallelArraySort && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ParallelArraySort(field=$field, other=$other)"

        private fun valueFields(): List<Any?> = listOf(field, other)

        companion object {
            /** A [ParallelArraySort] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>, other: QueryField<*>): ParallelArraySort = ParallelArraySort(field, other)
        }
    }

    /** `EQ` / `NE` on [field] names an array operand, and the storage cannot compare a whole array. */
    class ArrayEquality @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.ARRAY_EQUALITY

        override val message: String
            get() = "Field [${this.field}] cannot be compared to an array; the storage supports only scalar operands."

        override fun equals(other: Any?): Boolean =
            this === other || other is ArrayEquality && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ArrayEquality(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [ArrayEquality] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): ArrayEquality = ArrayEquality(field)
        }
    }

    /** A sort names more fields than any storage supports. */
    class SortTooMany @InternalWowApi constructor(val max: Int) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.SORT_TOO_MANY

        override val message: String
            get() = "Sort must contain at most $max fields."

        override fun equals(other: Any?): Boolean =
            this === other || other is SortTooMany && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "SortTooMany(max=$max)"

        private fun valueFields(): List<Any?> = listOf(max)

        companion object {
            /** A [SortTooMany] of the given values. */
            @JvmStatic
            fun of(max: Int): SortTooMany = SortTooMany(max)
        }
    }

    /** Two sort fields ([field] is the later one) are bound to the same physical field, so one of them sorts nothing. */
    class DuplicateSortField @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.SORT_FIELD_DUPLICATE

        override val message: String
            get() = "Sort field [${this.field}] maps to the same physical field as an earlier sort field."

        override fun equals(other: Any?): Boolean =
            this === other || other is DuplicateSortField && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "DuplicateSortField(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [DuplicateSortField] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): DuplicateSortField = DuplicateSortField(field)
        }
    }

    /** An identity filter or a cursor (whose tie-breaker is the identity) on a [model] that defines no identity. */
    class IdentityUndefined @InternalWowApi constructor(val model: QueryModel) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.IDENTITY_UNDEFINED

        override val message: String
            get() = "Record identity is not defined for model [${model.value}]."

        override fun equals(other: Any?): Boolean =
            this === other || other is IdentityUndefined && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "IdentityUndefined(model=$model)"

        private fun valueFields(): List<Any?> = listOf(model)

        companion object {
            /** A [IdentityUndefined] of the given values. */
            @JvmStatic
            fun of(model: QueryModel): IdentityUndefined = IdentityUndefined(model)
        }
    }

    /**
     * The storage cannot run [feature]: it declares the feature unsupported, or its native language cannot express
     * the query (a backend's native check). [field] is the field involved, when there is one.
     */
    class StorageUnsupported @InternalWowApi constructor(
        val feature: String,
        override val field: QueryField<*>? = null
    ) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.STORAGE_UNSUPPORTED

        override val message: String
            get() = "Storage does not support $feature."

        override fun equals(other: Any?): Boolean =
            this === other || other is StorageUnsupported && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "StorageUnsupported(feature=$feature, field=$field)"

        private fun valueFields(): List<Any?> = listOf(feature, field)

        companion object {
            /** A [StorageUnsupported] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                feature: String,
                field: QueryField<*>? = null
            ): StorageUnsupported = StorageUnsupported(feature, field)
        }
    }

    /**
     * A PREVIOUS window steps along the date group on [field], but the filter conditions the field where its range
     * cannot be shifted: outside the top-level AND, or with a value that is not an instant.
     */
    class PreviousUnshiftable @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val code: String
            get() = QueryErrorCodes.PREVIOUS_UNSHIFTABLE

        override val message: String
            get() = "PREVIOUS reads the earlier period by shifting the filter's range of [${this.field}], so the " +
                "filter must condition the field only in its top-level AND, with instant values."

        override fun equals(other: Any?): Boolean =
            this === other || other is PreviousUnshiftable && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "PreviousUnshiftable(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [PreviousUnshiftable] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): PreviousUnshiftable = PreviousUnshiftable(field)
        }
    }

    /*
     * Request violations: the entry budget and gates of step 0 (QueryBudget, QueryEntryPolicy) and the cursor token.
     * [label] names the entry and opens the text, e.g. `HTTP page size[0] ...`.
     */

    /**
     * A size the entry bounds ([subject]: a list or aggregation limit, a page index, size, window or offset, a cursor
     * size) is [value], outside [min]..[max]; a `null` bound is open.
     */
    class SizeOutOfRange @InternalWowApi constructor(
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

        override fun equals(other: Any?): Boolean =
            this === other || other is SizeOutOfRange && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String =
            "SizeOutOfRange(label=$label, subject=$subject, value=$value, min=$min, max=$max, location=$location)"

        private fun valueFields(): List<Any?> = listOf(label, subject, value, min, max, location)

        companion object {
            /** A [SizeOutOfRange] of the given values. */
            @JvmStatic
            fun of(
                label: String,
                subject: String,
                value: Long,
                min: Long?,
                max: Long?,
                location: String,
            ): SizeOutOfRange = SizeOutOfRange(label, subject, value, min, max, location)
        }
    }

    /** The filter (and HAVING) of a query has more nodes or values ([subject]) than the entry allows. */
    class FilterTooLarge @InternalWowApi constructor(
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

        override fun equals(other: Any?): Boolean =
            this === other || other is FilterTooLarge && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String =
            "FilterTooLarge(label=$label, subject=$subject, value=$value, max=$max, location=$location)"

        private fun valueFields(): List<Any?> = listOf(label, subject, value, max, location)

        companion object {
            /** A [FilterTooLarge] of the given values. */
            @JvmStatic
            fun of(
                label: String,
                subject: String,
                value: Int,
                max: Int,
                location: String,
            ): FilterTooLarge = FilterTooLarge(label, subject, value, max, location)
        }
    }

    /** [construct] (e.g. `query operator[CONTAINS]`) is expensive, and the entry does not allow expensive operators. */
    class ExpensiveOperatorDisabled @InternalWowApi constructor(
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

        override fun equals(other: Any?): Boolean =
            this === other || other is ExpensiveOperatorDisabled && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String =
            "ExpensiveOperatorDisabled(label=$label, construct=$construct, plural=$plural, location=$location)"

        private fun valueFields(): List<Any?> = listOf(label, construct, plural, location)

        companion object {
            /** A [ExpensiveOperatorDisabled] of the given values. */
            @JvmStatic
            @JvmOverloads
            fun of(
                label: String,
                construct: String,
                plural: Boolean = false,
                location: String,
            ): ExpensiveOperatorDisabled = ExpensiveOperatorDisabled(label, construct, plural, location)
        }
    }

    /**
     * A paged query asks for an [EXACT][me.ahoo.wow.api.query.TotalMode.EXACT] total, and the entry counts only up to
     * its bound. An expensive operation the entry disables, so it shares that code.
     */
    class ExactCountDisabled @InternalWowApi constructor(val label: String) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = "total"

        override val code: String
            get() = QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED

        override val message: String
            get() = "$label exact total is disabled: ask for a BOUNDED total, or count with a limit."

        override fun equals(other: Any?): Boolean =
            this === other || other is ExactCountDisabled && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ExactCountDisabled(label=$label)"

        private fun valueFields(): List<Any?> = listOf(label)

        companion object {
            /** A [ExactCountDisabled] of the given values. */
            @JvmStatic
            fun of(label: String): ExactCountDisabled = ExactCountDisabled(label)
        }
    }

    /** A counting query (count, paged total) matches every record, and the entry does not allow expensive operators. */
    class CountRequiresFilter @InternalWowApi constructor(val label: String) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = FILTER

        override val code: String
            get() = QueryErrorCodes.COUNT_REQUIRES_FILTER

        override val message: String
            get() = "$label counting query must not match all documents."

        override fun equals(other: Any?): Boolean =
            this === other || other is CountRequiresFilter && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "CountRequiresFilter(label=$label)"

        private fun valueFields(): List<Any?> = listOf(label)

        companion object {
            /** A [CountRequiresFilter] of the given values. */
            @JvmStatic
            fun of(label: String): CountRequiresFilter = CountRequiresFilter(label)
        }
    }

    /**
     * An aggregation whose HAVING, metric sort or windows the core computes read more than [max] groups, dense fill
     * rows included. Metered while the rows arrive, so it can fail after the response started streaming.
     */
    class ResidualGroupsExceeded @InternalWowApi constructor(val label: String, val max: Int) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = QueryRequestException.BODY

        override val code: String
            get() = QueryErrorCodes.RESIDUAL_GROUPS_EXCEEDED

        override val message: String
            get() = "$label aggregation processes more than [$max] groups, dense fill included, to compute HAVING, " +
                "a metric sort or a window in the query service; narrow the filter or the period."

        override fun equals(other: Any?): Boolean =
            this === other || other is ResidualGroupsExceeded && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "ResidualGroupsExceeded(label=$label, max=$max)"

        private fun valueFields(): List<Any?> = listOf(label, max)

        companion object {
            /** A [ResidualGroupsExceeded] of the given values. */
            @JvmStatic
            fun of(label: String, max: Int): ResidualGroupsExceeded = ResidualGroupsExceeded(label, max)
        }
    }

    /**
     * An aggregation that names no sort found more than its [limit] groups. Its first [limit] groups in key order would
     * be a cut nobody asked for: a top-N needs a sort, and every group needs a higher limit.
     */
    class TooManyGroups @InternalWowApi constructor(val limit: Int) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = "limit"

        override val code: String
            get() = QueryErrorCodes.TOO_MANY_GROUPS

        override val message: String
            get() = "Aggregation has more than [$limit] groups and names no sort: add a sort for a top-N, or raise limit."

        override fun equals(other: Any?): Boolean = this === other || other is TooManyGroups && limit == other.limit

        override fun hashCode(): Int = limit.hashCode()

        override fun toString(): String = "TooManyGroups(limit=$limit)"

        /** Its own problem, not the generic invalid query: the query is well-formed, its answer too large. */
        override fun rejection(): RuntimeException =
            QueryRequestException(this, QueryErrors.TOO_MANY_GROUPS)

        companion object {
            /** A [TooManyGroups] of the given values. */
            @JvmStatic
            fun of(limit: Int): TooManyGroups = TooManyGroups(limit)
        }
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

    /**
     * A stream its store cannot resume (no keyset of its sort, so no positions) that names no limit: it would read a
     * list's default number of rows and stop, which a reader takes for every match.
     */
    data object StreamNotResumable : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = "limit"

        override val code: String
            get() = QueryErrorCodes.STREAM_NOT_RESUMABLE

        override val message: String
            get() = "This stream cannot be resumed, its sort having no keyset in its store: name a limit, or sort " +
                "by fields its store keeps a keyset of."
    }

    /** A cursor sort names [field] twice once the identity tie-breaker is appended. */
    class CursorSortDuplicate @InternalWowApi constructor(override val field: QueryField<*>) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val code: String
            get() = QueryErrorCodes.CURSOR_SORT_DUPLICATE

        override val message: String
            get() = "Cursor sort fields must be unique."

        override fun equals(other: Any?): Boolean =
            this === other || other is CursorSortDuplicate && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "CursorSortDuplicate(field=$field)"

        private fun valueFields(): List<Any?> = listOf(field)

        companion object {
            /** A [CursorSortDuplicate] of the given values. */
            @JvmStatic
            fun of(field: QueryField<*>): CursorSortDuplicate = CursorSortDuplicate(field)
        }
    }

    /** A cursor sort has more than [max] fields once the identity tie-breaker is appended. */
    class CursorSortTooMany @InternalWowApi constructor(val max: Int) : QueryViolation {
        override val kind: Kind
            get() = Kind.REQUEST

        override val location: String
            get() = "sort"

        override val code: String
            get() = QueryErrorCodes.CURSOR_SORT_TOO_MANY

        override val message: String
            get() = "Effective cursor sort must contain at most $max fields."

        override fun equals(other: Any?): Boolean =
            this === other || other is CursorSortTooMany && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "CursorSortTooMany(max=$max)"

        private fun valueFields(): List<Any?> = listOf(max)

        companion object {
            /** A [CursorSortTooMany] of the given values. */
            @JvmStatic
            fun of(max: Int): CursorSortTooMany = CursorSortTooMany(max)
        }
    }

    companion object {
        /** The request part a filter-wide rule names. */
        const val FILTER: String = "filter"
    }
}

internal inline fun requireValid(accepted: Boolean, violation: () -> QueryViolation) {
    if (!accepted) throw violation().rejection()
}
