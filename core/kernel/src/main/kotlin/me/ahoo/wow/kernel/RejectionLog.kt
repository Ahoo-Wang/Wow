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
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The one shape of a command's rejection in the log, under the logger [CATEGORY]: a readable message, and the fields
 * as SLF4J key-values named as the spans' attributes name them ([CONTEXT], [AGGREGATE], [COMMAND], [ERROR_TYPE],
 * [REQUEST_ID], and [WITH_EVENTS] for a decision's). A decision's rejection is a business outcome, at INFO
 * ([rejected]); an admission's refusal is the gateway's ordinary work, at DEBUG ([refused]).
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

    private val logger = KotlinLogging.logger(CATEGORY)

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
