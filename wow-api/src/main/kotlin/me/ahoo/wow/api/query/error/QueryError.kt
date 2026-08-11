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

package me.ahoo.wow.api.query.error

private val QUERY_ERROR_REASON_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")

enum class QueryErrorCode {
    INVALID_QUERY,
    POLICY_DENIED,
    POLICY_FAILURE,
    UNSUPPORTED_CAPABILITY,
    BACKEND_NOT_FOUND,
    BACKEND_NOT_READY,
    BUDGET_EXCEEDED,
    DEADLINE_EXCEEDED,
    RESULT_VALIDATION_FAILED,
    BACKEND_FAILURE,
    PARTIAL_RESULT,
    INCOMPLETE_RESULT
}

enum class QueryStage {
    ADMISSION,
    NORMALIZE,
    POLICY,
    BACKEND_RESOLUTION,
    PLANNING,
    EXECUTION,
    RESULT_POLICY
}

@JvmInline
value class QueryErrorReason(val value: String) {
    init {
        require(QUERY_ERROR_REASON_PATTERN.matches(value)) {
            "Query error reason must be an uppercase low-cardinality identifier."
        }
    }

    override fun toString(): String = value
}

class QueryException(
    val code: QueryErrorCode,
    val stage: QueryStage,
    val reason: QueryErrorReason
) : RuntimeException("${code.name}:${stage.name}:${reason.value}")
