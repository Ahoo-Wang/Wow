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
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * Tells a transient failure of Elasticsearch from a defect: the first is a [StorageUnavailableException], so the
 * runtime answers it as a retryable error, the second stays the client's exception.
 *
 * Transient are a request that never got an answer (a connection refused, reset or timed out: a [TransportException]
 * or an [IOException]) and an answer that says the cluster cannot take it now: `429` (rejected, a queue is full),
 * `502`, `503` (no master, shards not ready) and `504`.
 */
internal object ElasticsearchFailures {
    private val TRANSIENT_STATUSES = setOf(429, 502, 503, 504)

    /** The status Elasticsearch answers a write whose sequence number guard failed with. */
    const val CONFLICT = 409

    /** The status of a missing index or document. */
    const val NOT_FOUND = 404

    /** The status of a request Elasticsearch refused as malformed: a document it cannot parse, say. */
    const val BAD_REQUEST = 400

    /** Whether [failure] is a transient failure of the cluster or the connection. */
    fun isTransient(failure: Throwable): Boolean = when (failure) {
        is ElasticsearchException -> failure.status() in TRANSIENT_STATUSES
        is TransportException, is IOException -> true
        else -> false
    }

    /** [failure] as a [StorageUnavailableException] when it [isTransient], else as it is. */
    fun translate(failure: Throwable): Throwable =
        if (isTransient(failure)) {
            StorageUnavailableException("Elasticsearch is unavailable; retry later.", failure)
        } else {
            failure
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
     * [StorageUnavailableException] [translate] made of it.
     */
    fun isShardNotReady(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)
            .filterIsInstance<ElasticsearchException>()
            .any { exception ->
                exception.status() == UNAVAILABLE && (
                    exception.error().type() in SHARD_NOT_READY ||
                        exception.error().rootCause().any { it.type() in SHARD_NOT_READY }
                    )
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
 * Runs [block], again after a short pause while it fails because the index's shard is not ready yet
 * ([ElasticsearchFailures.isShardNotReady]), at most [attempts] times: the window right after an index is created or
 * fails over. Any other failure, or the last one, is thrown.
 */
internal suspend fun <T> whenShardReady(attempts: Int = SHARD_READY_ATTEMPTS, block: suspend () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (failure: StorageUnavailableException) {
            if (attempt >= attempts || !ElasticsearchFailures.isShardNotReady(failure)) throw failure
        }
        delay(SHARD_READY_PAUSE_MILLIS * attempt)
        attempt++
    }
}

/** How many times [whenShardReady] runs a call, and its first pause; the pauses grow linearly (about 5.5 s in all). */
internal const val SHARD_READY_ATTEMPTS = 10
internal const val SHARD_READY_PAUSE_MILLIS = 100L

/** Awaits this request: a transient failure thrown as a [StorageUnavailableException] ([ElasticsearchFailures]). */
internal suspend fun <T> CompletableFuture<T>.awaitElasticsearch(): T = try {
    await()
} catch (failure: ElasticsearchException) {
    throw ElasticsearchFailures.translate(failure)
} catch (failure: IOException) {
    // A TransportException too: the request got no answer Elasticsearch gave.
    throw ElasticsearchFailures.translate(failure)
}

/**
 * Classifies Elasticsearch's connection failures and overload answers as systemic (decision D243), for a handler that
 * writes to Elasticsearch itself: every aggregate of the partition would fail the same way, so the subscription pauses
 * it. Found by [java.util.ServiceLoader] (`META-INF/services`) when this backend is on the class path.
 */
internal class ElasticsearchFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? = when {
        failure is ElasticsearchException && ElasticsearchFailures.isTransient(failure) -> FailureClass.SYSTEMIC
        failure is TransportException -> FailureClass.SYSTEMIC
        else -> null
    }
}
