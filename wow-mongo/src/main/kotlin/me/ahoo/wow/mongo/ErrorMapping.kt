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

package me.ahoo.wow.mongo

import com.mongodb.ErrorCategory
import com.mongodb.MongoException
import com.mongodb.MongoWriteException
import com.mongodb.WriteError
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.exception.RecoverableException

private val RECOVERABLE_WRITE_ERROR_CODES = setOf(
    6, // HostUnreachable
    7, // HostNotFound
    89, // NetworkTimeout
    91, // ShutdownInProgress
    133, // StaleShardVersion / FailedToSatisfyReadPreference
    189, // PrimarySteppedDown
    262, // ExceededTimeLimit
    264, // StaleEpoch
    10107, // NotWritablePrimary
)

class RecoverableMongoWriteException(writeException: MongoWriteException) :
    MongoException(writeException.error.code, writeException.error.message, writeException),
    RecoverableException {
    val error: WriteError = writeException.error
}

fun WriteError.isRecoverableWriteError(): Boolean = code in RECOVERABLE_WRITE_ERROR_CODES

fun MongoWriteException.toWowError(eventStream: DomainEventStream): Throwable {
    if (ErrorCategory.fromErrorCode(error.code) == ErrorCategory.DUPLICATE_KEY) {
        if (error.message.contains(AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME)) {
            return EventVersionConflictException(
                eventStream = eventStream,
                cause = this,
            )
        }
        if (error.message.contains(AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME)) {
            return DuplicateRequestIdException(
                aggregateId = eventStream.aggregateId,
                requestId = eventStream.requestId,
                cause = this,
            )
        }
        return this
    }
    if (error.isRecoverableWriteError()) {
        return RecoverableMongoWriteException(this)
    }
    return this
}
