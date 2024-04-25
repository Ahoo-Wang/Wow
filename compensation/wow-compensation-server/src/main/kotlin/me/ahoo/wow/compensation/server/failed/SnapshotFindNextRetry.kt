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
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.RetryState
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.compensation.domain.FindNextRetry
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.query.listQuery
import me.ahoo.wow.query.nestedState
import me.ahoo.wow.query.query
import me.ahoo.wow.query.toState
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Primary
@Repository
class SnapshotFindNextRetry(
    private val queryService: SnapshotQueryService<ExecutionFailedState>
) : FindNextRetry {

    override fun findNextRetry(limit: Int): Flux<out IExecutionFailedState> {
        val currentTime = System.currentTimeMillis()
        return listQuery {
            limit(limit)
            condition {
                nestedState()
                ExecutionFailedState::recoverable ne RecoverableType.UNRECOVERABLE.name
                ExecutionFailedState::isRetryable eq true
                ExecutionFailedState::retryState nested {
                    RetryState::nextRetryAt lte currentTime
                }
                or {
                    ExecutionFailedState::status eq ExecutionFailedStatus.FAILED.name
                    and {
                        ExecutionFailedState::status eq ExecutionFailedStatus.PREPARED.name
                        ExecutionFailedState::retryState nested {
                            RetryState::timeoutAt lte currentTime
                        }
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