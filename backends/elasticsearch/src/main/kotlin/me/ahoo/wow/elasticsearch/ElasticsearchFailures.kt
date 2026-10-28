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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch._types.ElasticsearchException
import co.elastic.clients.transport.TransportException
import co.elastic.clients.transport.rest5_client.low_level.ResponseException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageUnavailableException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Tells a transient failure of Elasticsearch from a defect: the first is a [StorageUnavailableException], so the
 * runtime answers it as a retryable error, the second stays the client's exception.
 *
 * Transient are a request that never got an answer (a connection refused, reset or timed out: a [TransportException]
 * or an [IOException]) and an answer that says the cluster cannot take it now: `429` (rejected, a queue is full),
 * `502`, `503` (no master, shards not ready) and `504`; a `429` is a cluster at its capacity, a
 * [StorageBusyException].
 */
internal object ElasticsearchFailures {
    private val TRANSIENT_STATUSES = setOf(TOO_MANY_REQUESTS, 502, 503, 504)

    /** The status Elasticsearch answers a write whose sequence number guard failed with. */
    const val CONFLICT = 409

    /** The status of a request the cluster rejected at its capacity (a full queue). */
    const val TOO_MANY_REQUESTS = 429

    /** The status of a missing index or document. */
    const val NOT_FOUND = 404

    /** The status of a request Elasticsearch refused as malformed: a document it cannot parse, say. */
    const val BAD_REQUEST = 400

    /** Whether [status] is an answer that the cluster cannot take the request now. */
    fun isTransient(status: Int): Boolean = status in TRANSIENT_STATUSES

    /** Whether [failure] is a transient failure of the cluster or the connection. */
    fun isTransient(failure: Throwable): Boolean = when (failure) {
        is ElasticsearchException -> isTransient(failure.status())
        is TransportException, is IOException -> true
        else -> false
    }

    /**
     * [failure] as a [StorageBusyException] when it is a `429`, as a [StorageUnavailableException] when it
     * [isTransient], else as it is.
     */
    fun translate(failure: Throwable): Throwable = when {
        hasStatus(failure, TOO_MANY_REQUESTS) ->
            StorageBusyException("Elasticsearch is at its capacity; retry later.", failure)
        isTransient(failure) -> StorageUnavailableException("Elasticsearch is unavailable; retry later.", failure)
        else -> failure
    }

    /**
     * The failure of one item of a `_bulk` request, [what] it wrote, answered [status] with [reason]: a
     * [StorageUnavailableException] when the cluster cannot take it now ([isTransient]), a
     * [StorageRefusedException] when Elasticsearch refused the document ([BAD_REQUEST]), else an
     * [IllegalStateException].
     */
    fun itemFailure(status: Int, what: String, reason: String?): RuntimeException {
        val message = "Elasticsearch answered $status for $what: $reason"
        return when {
            status == TOO_MANY_REQUESTS -> StorageBusyException(message)
            isTransient(status) -> StorageUnavailableException(message)
            status == BAD_REQUEST -> StorageRefusedException(message)
            else -> IllegalStateException(message)
        }
    }

    /** Whether [failure] is Elasticsearch's answer of [status]. */
    fun hasStatus(
        failure: Throwable,
        status: Int
    ): Boolean = failure is ElasticsearchException && failure.status() == status

    /** Whether [failure] says the index does not exist. */
    fun isIndexNotFound(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)
            .any { it is ElasticsearchException && it.error().type() == INDEX_NOT_FOUND }

    /**
     * Whether [failure] says the index's shard cannot serve the request yet (a primary still recovering or not
     * assigned): Elasticsearch answers `503` with one of [SHARD_NOT_READY] while a new index starts. Unwraps the
     * [StorageUnavailableException] [translate] made of it. The answer reaches the client in either of two forms: an
     * [ElasticsearchException] whose error the client parsed, or, for a request whose endpoint takes no error body
     * (a real-time `GET` of a document, say), the low-level client's [ResponseException], whose message carries the
     * body as it came.
     */
    fun isShardNotReady(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)
            .any { cause ->
                when (cause) {
                    is ElasticsearchException -> cause.status() == UNAVAILABLE && (
                        cause.error().type() in SHARD_NOT_READY ||
                            cause.error().rootCause().any { it.type() in SHARD_NOT_READY }
                        )
                    is ResponseException ->
                        cause.response.statusCode == UNAVAILABLE &&
                            SHARD_NOT_READY.any { type -> "${cause.message}".contains("\"$type\"") }
                    else -> false
                }
            }

    private const val UNAVAILABLE = 503
    private val SHARD_NOT_READY = setOf(
        "no_shard_available_action_exception",
        "illegal_index_shard_state_exception",
        "unavailable_shards_exception",
        "primary_missing_action_exception",
    )

    private const val INDEX_NOT_FOUND = "index_not_found_exception"
    private const val MAX_CAUSE_DEPTH = 8
}

/**
 * Runs [block], again after a pause while it fails because the index's shard is not ready yet
 * ([ElasticsearchFailures.isShardNotReady]), for at most [timeout]: the window right after an index is created or
 * fails over, which a loaded cluster may take seconds to close. The pauses start at [SHARD_READY_FIRST_PAUSE] and
 * double up to [SHARD_READY_MAX_PAUSE]. Any other failure, or the last one, is thrown.
 */
internal suspend fun <T> whenShardReady(timeout: Duration = SHARD_READY_TIMEOUT, block: suspend () -> T): T {
    val start = TimeSource.Monotonic.markNow()
    var pause = SHARD_READY_FIRST_PAUSE
    while (true) {
        try {
            return block()
        } catch (failure: StorageUnavailableException) {
            if (start.elapsedNow() + pause > timeout || !ElasticsearchFailures.isShardNotReady(failure)) throw failure
        }
        delay(pause)
        pause = minOf(pause * 2, SHARD_READY_MAX_PAUSE)
    }
}

/** How long [whenShardReady] retries a call, as long as [ElasticsearchIndices.awaitActive] waits for a primary. */
internal val SHARD_READY_TIMEOUT: Duration = 30.seconds
internal val SHARD_READY_FIRST_PAUSE: Duration = 100.milliseconds
internal val SHARD_READY_MAX_PAUSE: Duration = 1.seconds

/** Awaits this request: a transient failure thrown as a [StorageUnavailableException] ([ElasticsearchFailures]). */
internal suspend fun <T> CompletableFuture<T>.awaitElasticsearch(): T = try {
    await()
} catch (failure: ElasticsearchException) {
    throw ElasticsearchFailures.translate(failure)
} catch (failure: IOException) {
    // A TransportException too: the request got no answer Elasticsearch gave.
    throw ElasticsearchFailures.translate(failure)
}
