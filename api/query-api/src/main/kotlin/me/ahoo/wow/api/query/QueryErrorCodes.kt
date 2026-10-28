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

package me.ahoo.wow.api.query

/**
 * Stable, machine-readable codes of rejected query requests, carried as `BindingError.code`. A contract: codes are
 * added, never renamed or reused. Clients switch on them instead of on the human text.
 */
object QueryErrorCodes {
    // Reading and decoding the request body; BindingError.name is the JSON path, or "body".
    const val INVALID_JSON = "invalid-json"
    const val BODY_NOT_OBJECT = "body-not-object"
    const val EMPTY_BODY = "empty-body"
    const val UNKNOWN_PROPERTY = "unknown-property"
    const val UNKNOWN_TYPE = "unknown-type"
    const val UNKNOWN_VALUE = "unknown-value"
    const val INVALID_VALUE = "invalid-value"
    const val INVALID_REQUEST = "invalid-request"
    const val CURSOR_SORT_DUPLICATE = "cursor-sort-duplicate"
    const val CURSOR_SORT_TOO_MANY = "cursor-sort-too-many"
    const val INVALID_CURSOR = "invalid-cursor"

    // The entry budget and gates (errorCode IllegalArgument); BindingError.name is the request part the rule bounds.
    const val SIZE_OUT_OF_RANGE = "size-out-of-range"
    const val FILTER_TOO_LARGE = "filter-too-large"
    const val EXPENSIVE_OPERATOR_DISABLED = "expensive-operator-disabled"
    const val COUNT_REQUIRES_FILTER = "count-requires-filter"
    const val RESIDUAL_GROUPS_EXCEEDED = "residual-groups-exceeded"
    const val TOO_MANY_GROUPS = "too-many-groups"
    const val EXPLICIT_ENTRY_REQUIRED = "explicit-entry-required"

    // A state point read (GET …/{id}/state?version=|at=, …/state/trace); BindingError.name is the query parameter.
    // The state asked for is more commits away from the snapshot, or from version 1, than a state read replays.
    const val REPLAY_TOO_LONG = "replay-too-long"

    // Admission against the query model; BindingError.name is the absolute logical field path (for an element-scoped
    // field, the full path such as items.price), or "" for model-level violations.
    const val UNKNOWN_FIELD = "unknown-field"
    const val UNSUPPORTED_CAPABILITY = "unsupported-capability"
    const val ELEMENT_SCOPE_REQUIRED = "element-scope-required"
    const val VALUE_MISMATCH = "value-mismatch"
    const val NOT_COLLECTION = "not-collection"
    const val NOT_SINGLE_STRING = "not-single-string"
    const val MODEL_SEARCH_UNSUPPORTED = "model-search-unsupported"
    const val CURSOR_NOT_ALLOWED = "cursor-not-allowed"
    const val PROTECTED_AGGREGATION = "protected-aggregation"
    const val PROTECTED_COMPARISON = "protected-comparison"
    const val MISSING_KEY_REQUIRES_STRING = "missing-key-requires-string"
    const val ANY_REQUIRES_SINGLE_VALUE = "any-requires-single-value"
    const val INCOMPLETE_PROJECTION = "incomplete-projection"
    const val METRIC_FILTER_SEARCH = "metric-filter-search"
    const val METRIC_FILTER_ELEMENT_MATCH = "metric-filter-element-match"
    const val METRIC_FILTER_ARRAY_FIELD = "metric-filter-array-field"
    const val NOT_PROJECTABLE = "not-projectable"
    const val EVENT_PROJECTION_TYPE_REQUIRED = "event-projection-type-required"
    const val TEMPORAL_REPRESENTATION_REQUIRED = "temporal-representation-required"
    const val TEMPORAL_CONFIGURATION_CONFLICT = "temporal-configuration-conflict"
    const val PARALLEL_ARRAY_SORT = "parallel-array-sort"
    const val ARRAY_EQUALITY = "array-equality"
    const val FIRST_LAST_REQUIRES_SINGLE_VALUE = "first-last-requires-single-value"
    const val FIRST_LAST_REQUIRES_ORDER_BY = "first-last-requires-order-by"
    const val TEMPORAL_AGGREGATION_UNSUPPORTED = "temporal-aggregation-unsupported"
    const val SORT_TOO_MANY = "sort-too-many"
    const val SORT_FIELD_DUPLICATE = "sort-field-duplicate"
    const val IDENTITY_UNDEFINED = "identity-undefined"
    const val STORAGE_UNSUPPORTED = "storage-unsupported"
    const val PREVIOUS_UNSHIFTABLE = "previous-unshiftable"
}
