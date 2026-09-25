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

/**
 * Why a query does not fit its model, as structured facts. [message] renders the REST-visible text, which is a
 * compatibility contract: changing a template changes the REST API.
 */
sealed interface QueryViolation {
    val message: String

    /** A stable, machine-readable code; a contract: codes are added, never renamed. */
    val code: String

    /** The logical field the violation is about, or `null` for model-level violations. */
    val field: QueryField?
        get() = null

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
}

internal inline fun requireValid(accepted: Boolean, violation: () -> QueryViolation) {
    if (!accepted) throw QuerySchemaValidationException(violation())
}
