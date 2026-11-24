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

package me.ahoo.wow.kernel

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer

/**
 * The one shape of a command's rejection in the log, under the logger [CATEGORY]: a readable message, and the fields
 * as SLF4J key-values named as the spans' attributes name them ([CONTEXT], [AGGREGATE], [COMMAND], [ERROR_TYPE],
 * [REQUEST_ID], and [WITH_EVENTS] for a decision's). A decision's rejection is a business outcome, at INFO
 * ([rejected]); an admission's refusal is the gateway's ordinary work, at DEBUG ([refused]).
 *
 * A saga's `@OnRejected` handler that accepts a rejection (derives nothing) is logged under a logger of its own,
 * [ACCEPTED_CATEGORY], at INFO ([accepted]), in the saga's service: the target's line, in the target's service, does
 * not say which handler took the rejection, and for a command to another context it is in another service's log.
 */
@InternalWowApi
object RejectionLog {
    const val CATEGORY: String = "wow.command.rejection"
    const val CONTEXT: String = "wow.context"
    const val AGGREGATE: String = "wow.aggregate.name"
    const val COMMAND: String = "wow.command.name"
    const val ERROR_TYPE: String = "error.type"
    const val REQUEST_ID: String = "wow.request.id"
    const val WITH_EVENTS: String = "wow.with_events"
    const val ACCEPTED_CATEGORY: String = "wow.saga.rejection"
    const val SUBSCRIPTION: String = "wow.subscription"
    const val COMMAND_ID: String = "wow.command.id"

    private val logger = KotlinLogging.logger(CATEGORY)
    private val accepting = KotlinLogging.logger(ACCEPTED_CATEGORY)

    /** [command] on [ref] rejected by its decision with [code] and [message]; [withEvents] if its events committed. */
    fun rejected(
        ref: AggregateRef,
        command: String,
        code: String,
        requestId: String,
        withEvents: Boolean,
        message: String
    ) {
        logger.atInfo {
            this.message = "Rejected $command on $ref with $code (requestId=$requestId, withEvents=$withEvents): $message"
            payload = fields(ref, command, code, requestId) + (WITH_EVENTS to withEvents)
        }
    }

    /**
     * The saga's `@OnRejected` [handler], of the saga's [context], accepted the rejection with [code] of [command]
     * [commandId] on [ref], deriving nothing.
     */
    fun accepted(
        context: String,
        handler: String,
        ref: AggregateRef,
        command: String,
        commandId: String,
        code: String
    ) {
        accepting.atInfo {
            message = "$handler in $context accepted the rejection of $command $commandId on $ref: $code."
            payload = mapOf(
                SUBSCRIPTION to handler,
                CONTEXT to ref.type.context,
                AGGREGATE to ref.type.name,
                COMMAND to command,
                COMMAND_ID to commandId,
                ERROR_TYPE to code,
            )
        }
    }

    /** [command] on [ref] refused at admission with [code]. */
    fun refused(ref: AggregateRef, command: String, code: String, requestId: String) {
        logger.atDebug {
            message = "Refused $command on $ref with $code (requestId=$requestId)."
            payload = fields(ref, command, code, requestId)
        }
    }

    private fun fields(ref: AggregateRef, command: String, code: String, requestId: String): Map<String, Any?> = mapOf(
        CONTEXT to ref.type.context,
        AGGREGATE to ref.type.name,
        COMMAND to command,
        ERROR_TYPE to code,
        REQUEST_ID to requestId,
    )
}

/**
 * Observes and logs that [command] of [commandType] on [type] was rejected: a business outcome, so at INFO
 * ([RejectionLog]).
 * [withEvents] is whether its events were committed with it.
 */
internal fun Observer.rejected(
    type: AggregateType,
    commandType: String,
    rejection: Rejection,
    command: Command<*>,
    withEvents: Boolean,
) {
    observed(ObservedEvent.Rejected(type, commandType, rejection.code, withEvents))
    RejectionLog.rejected(
        command.ref,
        commandType,
        rejection.code,
        command.requestId,
        withEvents,
        rejection.message
    )
}
