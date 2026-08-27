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

import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.exception.ErrorCodes.DUPLICATE_AGGREGATE_ID
import me.ahoo.wow.exception.ErrorCodes.EVENT_VERSION_CONFLICT
import me.ahoo.wow.exception.RecoverableException
import me.ahoo.wow.exception.WowException

/**
 * Exception thrown when there's a version conflict during event appending.
 * This indicates that the expected version does not match the current version of the aggregate.
 *
 * @param eventStream the domain event stream that caused the conflict
 * @param errorMsg the error message (default: version conflict message)
 * @param cause the underlying cause of the exception
 */
class EventVersionConflictException(
    val eventStream: DomainEventStream,
    errorMsg: String = "Event Version[${eventStream.version}] conflict.",
    cause: Throwable? = null
) : WowException(
    EVENT_VERSION_CONFLICT,
    errorMsg,
    cause,
),
    RecoverableException

/**
 * Exception thrown when attempting to create an aggregate with an ID that already exists.
 * This typically occurs when trying to append events for an aggregate that has already been initialized.
 *
 * @param eventStream the domain event stream that caused the duplicate
 * @param errorMsg the error message (default: duplicate aggregate ID message)
 * @param cause the underlying cause of the exception
 */
class DuplicateAggregateIdException(
    val eventStream: DomainEventStream,
    errorMsg: String = "Duplicate ${eventStream.aggregateId}.",
    cause: Throwable? = null
) : WowException(
    errorCode = DUPLICATE_AGGREGATE_ID,
    errorMsg = errorMsg,
    cause = cause,
)
