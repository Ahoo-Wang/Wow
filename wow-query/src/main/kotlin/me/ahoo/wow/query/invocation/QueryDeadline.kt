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

package me.ahoo.wow.query.invocation

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant

internal object QueryDeadline {
    fun from(
        frozenInstant: Instant,
        timeout: Duration?,
        stage: QueryStage = QueryStage.ADMISSION
    ): Instant? = when {
        timeout == null -> null
        timeout.isZero -> frozenInstant
        else -> try {
            frozenInstant.plus(timeout)
        } catch (_: DateTimeException) {
            throw invalidDeadline(stage)
        } catch (_: ArithmeticException) {
            throw invalidDeadline(stage)
        }
    }

    private fun invalidDeadline(stage: QueryStage): QueryException = QueryException(
        QueryErrorCode.INVALID_QUERY,
        stage,
        QueryErrorReason.INVALID_REQUEST
    )
}
