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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability

/**
 * Why a query does not fit its model, as structured facts. [message] renders the REST-visible text, which is a
 * compatibility contract: changing a template changes the REST API.
 */
sealed interface QueryViolation {
    val message: String

    data class UnknownField(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Unknown logical field [${this.field}]."
    }

    /** The field grants none of [capabilities]. */
    data class UnsupportedCapability(val field: QueryField, val capabilities: Set<QueryCapability>) : QueryViolation {
        override val message: String
            get() = "Field [${this.field}] does not support [${capabilities.joinToString(" or ")}]."
    }

    data class ElementScopeRequired(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Field [${this.field}] requires its declared element scope."
    }

    /** A filter value falls outside the field's declared domain; [field] is the name as the request wrote it. */
    data class ValueMismatch(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Filter value does not match [${this.field}]."
    }

    data class NotCollection(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Field [${this.field}] is not a known collection."
    }

    data class NotSingleString(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Field [${this.field}] is not a single string."
    }

    data object ModelSearchUnsupported : QueryViolation {
        override val message: String
            get() = "Model search is unsupported."
    }

    data class CursorNotAllowed(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Field [${this.field}] cannot be used for a cursor."
    }

    data class ProtectedAggregation(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Protected field [${this.field}] cannot be aggregated."
    }

    data class MissingKeyRequiresString(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Field [${this.field}] must be a single-valued string field to declare missingKey."
    }

    data object AnyRequiresSingleValue : QueryViolation {
        override val message: String
            get() = "ANY requires a single value."
    }

    data object IncompleteProjection : QueryViolation {
        override val message: String
            get() = "Native storage cannot deliver a complete source projection; select available fields explicitly."
    }

    data object MetricFilterSearch : QueryViolation {
        override val message: String
            get() = "Aggregation metric filters do not support search filters."
    }

    data object MetricFilterElementMatch : QueryViolation {
        override val message: String
            get() = "Aggregation metric filters do not support [ELEMENT_MATCH]."
    }

    data class MetricFilterArrayField(val field: QueryField) : QueryViolation {
        override val message: String
            get() = "Aggregation metric filter field [${this.field}] must be scalar; array fields are not supported in " +
                "metric filters."
    }
}

internal inline fun requireValid(accepted: Boolean, violation: () -> QueryViolation) {
    if (!accepted) throw QuerySchemaValidationException(violation())
}
