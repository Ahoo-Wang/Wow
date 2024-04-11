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

package me.ahoo.wow.compensation.server.failed

import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.compensation.CompensationService.EXECUTION_FAILED_AGGREGATE_NAME
import me.ahoo.wow.compensation.CompensationService.SERVICE_ALIAS
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.compensation.domain.FindNextRetry
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.query.query
import me.ahoo.wow.query.toState
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Primary
@Repository
class SnapshotFindNextRetry(
    private val queryService: SnapshotQueryService<ExecutionFailedState>
) : FindNextRetry {
    companion object {
        private const val STATE_FIELD_PREFIX = "state."
        private const val STATUS_FIELD = STATE_FIELD_PREFIX + "status"
        private const val RECOVERABLE_FIELD = STATE_FIELD_PREFIX + "recoverable"
        private const val IS_RETRYABLE_FIELD = STATE_FIELD_PREFIX + "isRetryable"
        private const val RETRY_STATE_FIELD = STATE_FIELD_PREFIX + "retryState"
        private const val RETRY_STATE_FIELD_PREFIX = "$RETRY_STATE_FIELD."
        private const val NEXT_RETRY_AT_FIELD = RETRY_STATE_FIELD_PREFIX + "nextRetryAt"
        private const val TIMEOUT_AT_FIELD = RETRY_STATE_FIELD_PREFIX + "timeoutAt"
    }

    override fun findNextRetry(limit: Int): Flux<out IExecutionFailedState> {
        val currentTime = System.currentTimeMillis()
        return query {
            limit(limit)
            condition {
                RECOVERABLE_FIELD ne RecoverableType.UNRECOVERABLE.name
                IS_RETRYABLE_FIELD eq true
                NEXT_RETRY_AT_FIELD lte currentTime
                or {
                    STATUS_FIELD eq ExecutionFailedStatus.FAILED.name
                    and {
                        STATUS_FIELD eq ExecutionFailedStatus.PREPARED.name
                        TIMEOUT_AT_FIELD lte currentTime
                    }
                }
            }
            sort {
                MessageRecords.VERSION.asc()
            }
        }.query(queryService)
            .toState()
    }

}