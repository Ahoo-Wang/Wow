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

package me.ahoo.wow.query.error

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import java.time.Duration

/**
 * A query the caller got right that the server failed to run: a cursor position that does not encode, a stored record
 * or backend row that breaks integrity (a non-finite number), a storage that failed. [QueryErrors.FAILED] (HTTP 500),
 * never a [me.ahoo.wow.query.schema.QueryViolation]: the caller cannot fix it by changing the request.
 *
 * [message] is returned to clients, so it names what failed without internal detail; [cause] stays server-side.
 */
open class QueryExecutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause), QueryError {
    override val errorCode: String
        get() = QueryErrors.FAILED

    override val errorMsg: String
        get() = checkNotNull(message)
}

/** Throws a [QueryExecutionException] with [message] unless [value] holds. */
internal inline fun checkExecution(value: Boolean, message: () -> String) {
    if (!value) throw QueryExecutionException(message())
}

/**
 * A query that ran out of the execution time its budget gives it (decision D150): [QueryErrors.TIMEOUT] (HTTP 503).
 * The backend received the same [timeout] to bound the statement natively (`statement_timeout`, `maxTimeMS`); the core
 * enforces it as well, so a backend that ignores it still fails here.
 */
class QueryTimeoutException(
    val timeout: Duration,
    cause: Throwable? = null,
) : QueryExecutionException("Query exceeded its execution timeout of ${timeout.toMillis()} ms.", cause) {
    override val errorCode: String
        get() = QueryErrors.TIMEOUT
}

/**
 * A query the storage could not run because it was unreachable or did not answer in time, a backend's
 * [me.ahoo.wow.spi.StorageUnavailableException] as [cause]: [QueryErrors.STORAGE_UNAVAILABLE] (HTTP 503). The same
 * query may pass once the storage is back.
 */
@InternalWowApi
class QueryStorageUnavailableException(cause: Throwable? = null) :
    QueryExecutionException("Query storage is unavailable; retry later.", cause) {
    override val errorCode: String
        get() = QueryErrors.STORAGE_UNAVAILABLE
}

/**
 * A query its store's [QueryBulkhead] did not admit (review R3.4-2): the [store] already runs [maxConcurrent] queries,
 * so this one was not run. [QueryErrors.BUSY] (HTTP 429, with `Retry-After`); the same query may pass later. The
 * message names no store: its name is the server's configuration.
 */
class QueryBusyException(
    val store: String,
    val maxConcurrent: Int,
) : QueryExecutionException(
    "The query's store already runs as many queries as it allows at once; retry later.",
) {
    override val errorCode: String
        get() = QueryErrors.BUSY
}

/**
 * A query of a dataset that is served but cannot be queried now ([me.ahoo.wow.query.dataset.QueryDataset.unavailable],
 * decision D259 7.2b-2): [QueryErrors.DATASET_UNAVAILABLE] (HTTP 409). The message names the dataset and the
 * [reason], fixed words such as `needs revision`; what made it so stays with whoever marked it.
 */
class QueryDatasetUnavailableException(
    val dataset: AggregateType,
    val reason: String,
) : QueryExecutionException("Dataset [$dataset] cannot be queried now: $reason.") {
    override val errorCode: String
        get() = QueryErrors.DATASET_UNAVAILABLE
}
