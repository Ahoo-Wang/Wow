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
    const val INVALID_JSON = "INVALID_JSON"
    const val BODY_NOT_OBJECT = "BODY_NOT_OBJECT"
    const val EMPTY_BODY = "EMPTY_BODY"
    const val UNKNOWN_PROPERTY = "UNKNOWN_PROPERTY"
    const val UNKNOWN_TYPE = "UNKNOWN_TYPE"
    const val UNKNOWN_VALUE = "UNKNOWN_VALUE"
    const val INVALID_VALUE = "INVALID_VALUE"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val CURSOR_SORT_DUPLICATE = "CURSOR_SORT_DUPLICATE"
    const val CURSOR_SORT_TOO_MANY = "CURSOR_SORT_TOO_MANY"

    // Admission against the query model; BindingError.name is the absolute logical field path (for an element-scoped
    // field, the full path such as items.price), or "" for model-level violations.
    const val UNKNOWN_FIELD = "UNKNOWN_FIELD"
    const val UNSUPPORTED_CAPABILITY = "UNSUPPORTED_CAPABILITY"
    const val ELEMENT_SCOPE_REQUIRED = "ELEMENT_SCOPE_REQUIRED"
    const val VALUE_MISMATCH = "VALUE_MISMATCH"
    const val NOT_COLLECTION = "NOT_COLLECTION"
    const val NOT_SINGLE_STRING = "NOT_SINGLE_STRING"
    const val MODEL_SEARCH_UNSUPPORTED = "MODEL_SEARCH_UNSUPPORTED"
    const val CURSOR_NOT_ALLOWED = "CURSOR_NOT_ALLOWED"
    const val PROTECTED_AGGREGATION = "PROTECTED_AGGREGATION"
    const val MISSING_KEY_REQUIRES_STRING = "MISSING_KEY_REQUIRES_STRING"
    const val ANY_REQUIRES_SINGLE_VALUE = "ANY_REQUIRES_SINGLE_VALUE"
    const val INCOMPLETE_PROJECTION = "INCOMPLETE_PROJECTION"
    const val METRIC_FILTER_SEARCH = "METRIC_FILTER_SEARCH"
    const val METRIC_FILTER_ELEMENT_MATCH = "METRIC_FILTER_ELEMENT_MATCH"
    const val METRIC_FILTER_ARRAY_FIELD = "METRIC_FILTER_ARRAY_FIELD"
    const val NOT_PROJECTABLE = "NOT_PROJECTABLE"
    const val EVENT_PROJECTION_TYPE_REQUIRED = "EVENT_PROJECTION_TYPE_REQUIRED"
    const val TEMPORAL_REPRESENTATION_REQUIRED = "TEMPORAL_REPRESENTATION_REQUIRED"
    const val TEMPORAL_CONFIGURATION_CONFLICT = "TEMPORAL_CONFIGURATION_CONFLICT"
}
