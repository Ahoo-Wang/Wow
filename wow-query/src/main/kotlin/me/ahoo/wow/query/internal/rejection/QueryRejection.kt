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

package me.ahoo.wow.query.internal.rejection

internal enum class QueryRejectionCategory {
    ACCESS_DENIED,
    INVALID_QUERY,
    INVALID_CURSOR,
    BUDGET_EXCEEDED,
    UNSUPPORTED_FEATURE,
    BACKEND_UNAVAILABLE,
    BACKEND_TIMEOUT,
    INCOMPLETE_RESULT,
    MAPPING_FAILURE,
    INTERNAL_FAILURE,
}

internal enum class QueryRejectionCode {
    CONDITION_DEPTH_LIMIT_EXCEEDED,
    CONDITION_NODE_LIMIT_EXCEEDED,
    CHILDREN_LIMIT_EXCEEDED,
    COLLECTION_LIMIT_EXCEEDED,
    OBJECT_LIMIT_EXCEEDED,
    VALUE_DEPTH_LIMIT_EXCEEDED,
    VALUE_NODE_LIMIT_EXCEEDED,
    NUMERIC_PRECISION_LIMIT_EXCEEDED,
    BYTE_ARRAY_LIMIT_EXCEEDED,
    PAYLOAD_LIMIT_EXCEEDED,
    PROJECTION_LIMIT_EXCEEDED,
    SORT_LIMIT_EXCEEDED,
    OPTIONS_LIMIT_EXCEEDED,
    STRING_LIMIT_EXCEEDED,
    CYCLIC_INPUT,
    INVALID_CHILDREN,
    FIELD_REQUIRED,
    INVALID_FIELD,
    INVALID_VALUE_TYPE,
    INVALID_VALUE_ARITY,
    DUPLICATE_OBJECT_KEY,
    INVALID_OPTION_TYPE,
    INVALID_OPTION_VALUE,
    UNKNOWN_OPTION,
    OPTION_NOT_ALLOWED,
    INVALID_TIME_VALUE,
    INVALID_LIMIT,
    INVALID_PAGE,
    INVALID_PROJECTION,
    INVALID_SORT,
    SYSTEM_FIELD_IN_ELEMENT_SCOPE,
    NATIVE_BACKEND_UNBOUND,
    INVALID_INVOCATION,
    TARGET_SCHEMA_MISMATCH,
    FIELD_NOT_FOUND,
    SEARCH_SCOPE_NOT_FOUND,
    OPERATOR_NOT_ALLOWED,
    CAPABILITY_UNAVAILABLE,
    CASE_INSENSITIVE_UNSUPPORTED,
    TYPED_PROJECTION_NOT_ALLOWED,
    DUPLICATE_SORT,
    NATIVE_BACKEND_CONFLICT,
    MANDATORY_NATIVE_NOT_ALLOWED,
    ANALYTICS_DOCUMENT_KIND_UNSUPPORTED,
    ANALYTICS_DIMENSION_TYPE_UNSUPPORTED,
    ANALYTICS_METRIC_TYPE_UNSUPPORTED,
    ANALYTICS_NUMERIC_POLICY_REQUIRED,
    ANALYTICS_NUMERIC_POLICY_UNSUPPORTED,
    ANALYTICS_HAVING_UNSUPPORTED,
    ANALYTICS_ORDER_UNSUPPORTED,
    ANALYTICS_CONSISTENCY_UNSUPPORTED,
    ANALYTICS_COMPLETENESS_UNSUPPORTED,
    DUPLICATE_ANALYTICS_ALIAS,
    INVALID_CURSOR_BINDING,
    VALUE_TYPE_MISMATCH,
    UNBOUNDED_STREAM_DISALLOWED,
    PAGE_WINDOW_EXCEEDED,
    ANALYTICS_DIMENSION_LIMIT_EXCEEDED,
    ANALYTICS_METRIC_LIMIT_EXCEEDED,
    ANALYTICS_BUCKET_LIMIT_EXCEEDED,
    AUTHORITY_REQUIRED,
    AUTHORITY_RESOLUTION_FAILED,
    LEGACY_CALLER_NOT_ALLOWED,
    DEADLINE_EXPIRED,
    POLICY_DENIED,
    POLICY_DECISION_MISSING,
    POLICY_EVALUATION_FAILED,
    POLICY_CONSTRAINT_INVALID,
    FILTER_FIELD_NOT_ALLOWED,
    SEARCH_SCOPE_NOT_ALLOWED,
    NATIVE_BACKEND_NOT_ALLOWED,
    PROJECTION_FIELD_NOT_ALLOWED,
    SORT_FIELD_NOT_ALLOWED,
    ANALYTICS_DIMENSION_FIELD_NOT_ALLOWED,
    ANALYTICS_METRIC_FIELD_NOT_ALLOWED,
    RESULT_LIMIT_EXCEEDED,
    SCHEMA_NOT_REGISTERED,
    EXECUTION_MODE_UNSUPPORTED,
    EXECUTION_DECISION_INVALID,
    SHADOW_PROBE_UNBOUNDED_STREAM,
    SHADOW_SUPERVISOR_UNAVAILABLE,
    BACKEND_NOT_REGISTERED,
    BACKEND_SCHEMA_MISMATCH,
    BACKEND_OPERATION_UNSUPPORTED,
    BACKEND_CAPABILITY_MISMATCH,
    LEGACY_BACKEND_NOT_REGISTERED,
    LEGACY_LOWERING_UNSUPPORTED,
    MANDATORY_CONDITION_UNENFORCEABLE,
    BACKEND_EXECUTION_FAILED,
    BACKEND_TIMEOUT,
    INCOMPLETE_RESULT,
    RESULT_MAPPING_FAILED,
    UNEXPECTED_QUERY_FAILURE,
}

internal class QueryRejectionPath private constructor(
    private val segments: List<Segment>,
) {
    fun property(name: String): QueryRejectionPath = QueryRejectionPath(segments + Segment.Property(name))

    fun index(index: Int): QueryRejectionPath = QueryRejectionPath(segments + Segment.Index(index))

    fun key(key: String): QueryRejectionPath = QueryRejectionPath(segments + Segment.Key(key))

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryRejectionPath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = buildString {
        append('$')
        segments.forEach { segment ->
            when (segment) {
                is Segment.Property -> append('.').append(segment.name)
                is Segment.Index -> append('[').append(segment.value).append(']')
                is Segment.Key -> append("['").append(segment.value.escapeKey()).append("']")
            }
        }
    }

    private sealed interface Segment {
        data class Property(val name: String) : Segment

        data class Index(val value: Int) : Segment

        data class Key(val value: String) : Segment
    }

    private fun String.escapeKey(): String = buildString {
        this@escapeKey.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.isISOControl()) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    companion object {
        val ROOT: QueryRejectionPath = QueryRejectionPath(emptyList())
    }
}

internal data class QueryRejection(
    val category: QueryRejectionCategory,
    val path: QueryRejectionPath,
    val code: QueryRejectionCode,
)

internal class QueryRejectedException(
    val rejection: QueryRejection,
    cause: Throwable? = null,
) : IllegalArgumentException(
    "${rejection.category}/${rejection.code} at ${rejection.path}",
    cause,
)

internal fun rejectQuery(
    category: QueryRejectionCategory,
    path: QueryRejectionPath,
    code: QueryRejectionCode,
    cause: Throwable? = null,
): Nothing = throw QueryRejectedException(QueryRejection(category, path, code), cause)
