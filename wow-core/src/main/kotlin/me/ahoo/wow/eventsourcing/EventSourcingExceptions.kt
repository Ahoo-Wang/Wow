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

package me.ahoo.wow.eventsourcing

import me.ahoo.wow.api.exception.ConflictException
import me.ahoo.wow.api.exception.ErrorCodes
import me.ahoo.wow.api.exception.PreconditionFailedException
import me.ahoo.wow.api.exception.WowException
import me.ahoo.wow.api.exception.WowTransientException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.command.CommandAggregateErrorCodes

object EventSourcingErrorCodes {
    private const val PREFIX = "${ErrorCodes.PREFIX}ES-"
    const val EVENT_VERSION_CONFLICT = PREFIX + ErrorCodes.CONFLICT
    const val REQUEST_ID_IDEMPOTENCY = PREFIX + ErrorCodes.ILLEGAL_ARGUMENT
    const val DUPLICATE_AGGREGATE_ID = CommandAggregateErrorCodes.PREFIX + "409-0"
}

class EventVersionConflictException(val eventStream: DomainEventStream, cause: Throwable? = null) :
    ConflictException,
    WowTransientException(
        EventSourcingErrorCodes.EVENT_VERSION_CONFLICT,
        "Failed to append eventStream[${eventStream.id}]: Version[${eventStream.version}] conflict.",
        cause,
    )

class RequestIdIdempotencyException(val eventStream: DomainEventStream, cause: Throwable? = null) :
    PreconditionFailedException,
    WowException(
        EventSourcingErrorCodes.REQUEST_ID_IDEMPOTENCY,
        "Failed to append eventStream[${eventStream.id}]: Duplicate request ID[${eventStream.requestId}].",
        cause,
    )

class DuplicateAggregateIdException(
    val eventStream: DomainEventStream,
) : ConflictException,
    WowException(
        EventSourcingErrorCodes.DUPLICATE_AGGREGATE_ID,
        "Failed to append eventStream[${eventStream.id}]: Duplicate aggregateId[${eventStream.aggregateId.id}].",
    )
