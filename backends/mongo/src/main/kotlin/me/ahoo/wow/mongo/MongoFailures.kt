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

import com.mongodb.MongoConnectionPoolClearedException
import com.mongodb.MongoException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteConcernException
import com.mongodb.internal.connection.MongoWriteConcernWithResponseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import me.ahoo.wow.spi.StorageUnavailableException

/**
 * Tells a transient failure of MongoDB from a defect: the first is a [StorageUnavailableException], so the runtime
 * answers it as a retryable error, the second stays the driver's exception.
 *
 * Transient are a socket that failed or timed out ([MongoSocketException]), no server selected in time
 * ([MongoTimeoutException]), a pool cleared after a failure ([MongoConnectionPoolClearedException]), a primary that
 * stepped down or a node recovering ([MongoNotPrimaryException], [MongoNodeIsRecoveringException]), and an error the
 * server labels transient (`TransientTransactionError`, `RetryableWriteError`).
 */
internal object MongoFailures {
    /** The server's error code of a document beyond the BSON size limit. */
    const val BSON_OBJECT_TOO_LARGE = 10334

    private val TRANSIENT_LABELS = listOf(
        MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL,
        "RetryableWriteError",
    )

    /** Whether [failure] is a transient failure of the server or the connection. */
    fun isTransient(failure: MongoException): Boolean =
        failure is MongoSocketException ||
            failure is MongoTimeoutException ||
            failure is MongoConnectionPoolClearedException ||
            failure is MongoNotPrimaryException ||
            failure is MongoNodeIsRecoveringException ||
            TRANSIENT_LABELS.any(failure::hasErrorLabel)

    /**
     * Whether a write that failed with [failure] may have been applied all the same: a timeout (server selection, the
     * operation's time limit) or a failed socket left its result unknown, and so did a write concern the server could
     * not confirm, such as a primary that stepped down before a majority had the write (the write is on the primary,
     * kept if it replicated, rolled back if not; the driver reports it with the response it retries on, a
     * [MongoWriteConcernWithResponseException], when its retry fails too). Any other failure the server answered.
     */
    fun isWriteUnknown(failure: MongoException): Boolean =
        failure is MongoTimeoutException ||
            failure is MongoSocketException ||
            failure is MongoExecutionTimeoutException ||
            failure is MongoWriteConcernException ||
            failure is MongoWriteConcernWithResponseException

    /**
     * Whether [failure] is the server refusing a document beyond its size limit (`BSONObjectTooLarge`): the stream
     * written can never be stored, so it is refused rather than left unknown (finding R2.2-2). The driver usually
     * refuses such a document before sending it, with a `BsonMaximumSizeExceededException`, which is no
     * [MongoException].
     */
    fun isTooLarge(failure: MongoException): Boolean = failure.code == BSON_OBJECT_TOO_LARGE

    /** [failure] as a [StorageUnavailableException] when it [isTransient], else as it is. */
    fun translate(failure: MongoException): RuntimeException =
        if (isTransient(failure)) {
            StorageUnavailableException("MongoDB is unavailable; retry later.", failure)
        } else {
            failure
        }
}

/** Runs [block] on this dispatcher, a transient [MongoException] thrown as a [StorageUnavailableException]. */
internal suspend fun <T> CoroutineDispatcher.mongo(block: suspend CoroutineScope.() -> T): T = withContext(this) {
    try {
        block()
    } catch (failure: MongoException) {
        throw MongoFailures.translate(failure)
    }
}
