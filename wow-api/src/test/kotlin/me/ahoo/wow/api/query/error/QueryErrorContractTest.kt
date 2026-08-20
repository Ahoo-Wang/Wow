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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class QueryErrorContractTest {
    @Test
    fun `query exception keeps the three argument constructor and defaults cause code to null`() {
        val exception = QueryException(
            QueryErrorCode.BACKEND_FAILURE,
            QueryStage.EXECUTION,
            QueryErrorReason.BACKEND_EXECUTION_FAILED
        )

        exception.causeCode.assert().isNull()
        exception.cause.assert().isNull()
        exception.suppressed.toList().assert().isEmpty()
        exception.message.assert().isEqualTo("BACKEND_FAILURE:EXECUTION:BACKEND_EXECUTION_FAILED")
    }

    @Test
    fun `query exception exposes only an enum cause code without throwable details`() {
        val exception = QueryException(
            QueryErrorCode.INCOMPLETE_RESULT,
            QueryStage.EXECUTION,
            QueryErrorReason.INCOMPLETE_STREAM,
            QueryErrorCode.DEADLINE_EXCEEDED
        )

        exception.causeCode.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
        exception.cause.assert().isNull()
        exception.suppressed.toList().assert().isEmpty()
        exception.message.assert().isEqualTo("INCOMPLETE_RESULT:EXECUTION:INCOMPLETE_STREAM")
    }

    @Test
    fun `query error codes should expose only the final public contract`() {
        QueryErrorCode.entries.assert().containsExactly(
            QueryErrorCode.INVALID_QUERY,
            QueryErrorCode.POLICY_DENIED,
            QueryErrorCode.POLICY_FAILURE,
            QueryErrorCode.UNSUPPORTED_CAPABILITY,
            QueryErrorCode.BACKEND_NOT_READY,
            QueryErrorCode.BUDGET_EXCEEDED,
            QueryErrorCode.DEADLINE_EXCEEDED,
            QueryErrorCode.RESULT_VALIDATION_FAILED,
            QueryErrorCode.BACKEND_FAILURE,
            QueryErrorCode.INCOMPLETE_RESULT
        )
    }
}
