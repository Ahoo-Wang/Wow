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

package me.ahoo.wow.compensation.server.webhook

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionFailedErrorInfo
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.RecoverableMarked
import me.ahoo.wow.compensation.server.webhook.QuickNavigation.toNavAsMarkdown
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TemplateEngine {
    fun title(event: DomainEvent<*>): String {
        return when (event.body) {
            is ExecutionFailedCreated -> {
                "Execution Failed"
            }

            is ExecutionFailedApplied -> {
                "Execution Failed"
            }

            is CompensationPrepared -> {
                "Compensation Prepared"
            }

            is ExecutionSuccessApplied -> {
                "Execution Success"
            }

            is RecoverableMarked -> {
                "Recoverable Marked"
            }

            else -> {
                "N/A"
            }
        }
    }

    fun renderOnEvent(
        event: DomainEvent<*>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>,
        host: String
    ): String {
        val title = title(event)
        val root = state.state
        val function = root.function
        val eventId = root.eventId
        val retryState = root.retryState
        val statusStyle = when (root.status) {
            ExecutionFailedStatus.FAILED -> "warning"
            ExecutionFailedStatus.PREPARED -> "comment"
            ExecutionFailedStatus.SUCCEEDED -> "info"
        }
        val recoverableStyle = when (root.recoverable) {
            RecoverableType.UNRECOVERABLE -> "warning"
            RecoverableType.UNKNOWN -> "comment"
            RecoverableType.RECOVERABLE -> "info"
        }
        val eventBody = event.body
        val error = if (eventBody is ExecutionFailedErrorInfo) {
            """
## Error
- Code: `${eventBody.error.errorCode}`
- Message: ${eventBody.error.errorMsg}
            """.trimIndent()
        } else {
            ""
        }

        return """
# $title - ${retryState.retries}
- Id：${root.toNavAsMarkdown(host)}
- Processor: ${function.processorName}@${function.contextName}
- Function: ${function.name}
- ExecuteAt: ${
            LocalDateTime.ofInstant(Instant.ofEpochMilli(root.executeAt), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }
- Recoverable: <font color="$recoverableStyle">${root.recoverable}</font>
## Event Id
- Aggregate: ${eventId.aggregateId.aggregateName}@${eventId.aggregateId.contextName}
- AggregateId: `${eventId.aggregateId.id}`
- Id: `${eventId.id}`
- Version: `${eventId.version}`
## Retry State
- Retries: ${retryState.retries}(${root.retrySpec.maxRetries})
- RetryAt: ${
            LocalDateTime.ofInstant(Instant.ofEpochMilli(retryState.retryAt), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }
- NextRetryAt: ${
            LocalDateTime.ofInstant(Instant.ofEpochMilli(retryState.nextRetryAt), ZoneId.systemDefault()).format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            )
        }
- Status: <font color="$statusStyle">${root.status}</font>
$error
        """.trimIndent()
    }
}
