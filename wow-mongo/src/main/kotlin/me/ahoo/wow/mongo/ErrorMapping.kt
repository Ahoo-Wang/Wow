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
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoException
import com.mongodb.MongoWriteException
import com.mongodb.WriteError
import com.mongodb.bulk.WriteConcernError
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

class RecoverableMongoBulkWriteException(
    val error: WriteError,
    bulkWriteException: MongoBulkWriteException,
) : MongoException(error.code, error.message, bulkWriteException),
    RecoverableException

fun WriteError.isRecoverableWriteError(): Boolean = code in RECOVERABLE_WRITE_ERROR_CODES

fun MongoWriteException.toWowError(eventStream: DomainEventStream): Throwable {
    return error.toWowError(eventStream, this)
}

internal fun WriteError.toWowError(eventStream: DomainEventStream, cause: MongoException): Throwable {
    if (ErrorCategory.fromErrorCode(code) == ErrorCategory.DUPLICATE_KEY) {
        if (message.contains(AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME)) {
            return EventVersionConflictException(
                eventStream = eventStream,
                cause = cause,
            )
        }
        if (message.contains(AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME)) {
            return DuplicateRequestIdException(
                aggregateId = eventStream.aggregateId,
                requestId = eventStream.requestId,
                cause = cause,
            )
        }
        return cause
    }
    if (isRecoverableWriteError()) {
        return when (cause) {
            is MongoWriteException -> RecoverableMongoWriteException(cause)
            is MongoBulkWriteException -> RecoverableMongoBulkWriteException(this, cause)
            else -> cause
        }
    }
    return cause
}

internal fun WriteError.toWowError(cause: MongoBulkWriteException): Throwable {
    if (isRecoverableWriteError()) {
        return RecoverableMongoBulkWriteException(this, cause)
    }
    return cause
}

internal fun WriteConcernError.toWowError(cause: MongoBulkWriteException): Throwable {
    val writeError = WriteError(code, message, details)
    return writeError.toWowError(cause)
}
