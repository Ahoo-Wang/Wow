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
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
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
