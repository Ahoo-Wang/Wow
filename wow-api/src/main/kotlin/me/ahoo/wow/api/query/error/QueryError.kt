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

enum class QueryErrorCode {
    INVALID_QUERY,
    POLICY_DENIED,
    POLICY_FAILURE,
    UNSUPPORTED_CAPABILITY,
    BACKEND_NOT_READY,
    BUDGET_EXCEEDED,
    DEADLINE_EXCEEDED,
    RESULT_VALIDATION_FAILED,
    BACKEND_FAILURE,
    INCOMPLETE_RESULT
}

enum class QueryStage {
    ADMISSION,
    NORMALIZE,
    VALIDATION,
    POLICY,
    BACKEND_RESOLUTION,
    PLANNING,
    EXECUTION,
    RESULT_POLICY
}

enum class QueryErrorReason {
    INVALID_REQUEST,
    TENANT_SCOPE_DENIED,
    OWNER_SCOPE_DENIED,
    SPACE_SCOPE_DENIED,
    FIELD_ACCESS_DENIED,
    CAPABILITY_DENIED,
    POLICY_EVALUATION_FAILED,
    BACKEND_UNAVAILABLE,
    BUDGET_LIMIT_REACHED,
    DEADLINE_REACHED,
    RESULT_INVALID,
    BACKEND_EXECUTION_FAILED,
    INCOMPLETE_STREAM
}

class QueryException(
    val code: QueryErrorCode,
    val stage: QueryStage,
    val reason: QueryErrorReason,
    val causeCode: QueryErrorCode?
) : RuntimeException("${code.name}:${stage.name}:${reason.name}", null, false, true) {
    constructor(
        code: QueryErrorCode,
        stage: QueryStage,
        reason: QueryErrorReason
    ) : this(code, stage, reason, null)
}
