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

package me.ahoo.wow.bi.catalog

import com.clickhouse.client.api.query.QueryResponse
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val CLICKHOUSE_CATALOG_CLEANUP_THREADS: Int = 4
private const val CLICKHOUSE_CATALOG_CLEANUP_TTL_SECONDS: Int = 60
private val CLICKHOUSE_CATALOG_CLEANUP_SCHEDULER: Scheduler = Schedulers.newBoundedElastic(
    CLICKHOUSE_CATALOG_CLEANUP_THREADS,
    Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
    "wow-bi-catalog-cleanup",
    CLICKHOUSE_CATALOG_CLEANUP_TTL_SECONDS,
    true,
)

/**
 * Gives a catalog query's response exactly one owner, so it is closed exactly once.
 *
 * The response can arrive after the query was cancelled or timed out. The reader owns it once it [claim]s it
 * (PENDING -> CLAIMED) and closes it when done; a cancelled or abandoned query (-> CANCELLED) hands a response that
 * arrives later to the cleanup scheduler, because closing drains the HTTP stream and must not block the query thread.
 */
internal class QueryResponseLifecycle(
    responseFuture: CompletableFuture<QueryResponse>,
) : AutoCloseable {
    private val state = AtomicReference(QueryResponseState.PENDING)
    private val response = AtomicReference<QueryResponse?>()
    private val responseClosed = AtomicBoolean()
    private val cleanupScheduled = AtomicBoolean()

    init {
        responseFuture.whenComplete { completedResponse, _ ->
            if (completedResponse != null) {
                response.compareAndSet(null, completedResponse)
                if (state.get() == QueryResponseState.CANCELLED) {
                    cleanupCancelled()
                }
            }
        }
    }

    fun claim(completedResponse: QueryResponse): Boolean {
        response.compareAndSet(null, completedResponse)
        if (state.compareAndSet(QueryResponseState.PENDING, QueryResponseState.CLAIMED)) {
            return true
        }
        closeResponse(propagateFailure = false)
        return false
    }

    fun abandon() {
        if (transitionToCancelled()) {
            cleanupCancelled()
        }
    }

    fun cancel(): Boolean = transitionToCancelled()

    fun cleanupCancelled() {
        if (state.get() != QueryResponseState.CANCELLED || response.get() == null) {
            return
        }
        if (cleanupScheduled.compareAndSet(false, true)) {
            CLICKHOUSE_CATALOG_CLEANUP_SCHEDULER.schedule {
                closeResponse(propagateFailure = false)
            }
        }
    }

    private fun transitionToCancelled(): Boolean {
        while (true) {
            when (val currentState = state.get()) {
                QueryResponseState.PENDING,
                QueryResponseState.CLAIMED,
                -> if (state.compareAndSet(currentState, QueryResponseState.CANCELLED)) {
                    return true
                }

                QueryResponseState.CANCELLED,
                QueryResponseState.COMPLETED,
                -> return false
            }
        }
    }

    override fun close() {
        while (true) {
            when (val currentState = state.get()) {
                QueryResponseState.PENDING,
                QueryResponseState.CLAIMED,
                -> if (state.compareAndSet(currentState, QueryResponseState.COMPLETED)) {
                    closeResponse(propagateFailure = true)
                    return
                }

                QueryResponseState.CANCELLED -> {
                    closeResponse(propagateFailure = false)
                    return
                }

                QueryResponseState.COMPLETED -> return
            }
        }
    }

    private fun closeResponse(propagateFailure: Boolean) {
        val claimedResponse = response.get() ?: return
        if (!responseClosed.compareAndSet(false, true)) {
            return
        }
        if (propagateFailure) {
            claimedResponse.close()
        } else {
            runCatching { claimedResponse.close() }
        }
    }
}

private enum class QueryResponseState {
    PENDING,
    CLAIMED,
    CANCELLED,
    COMPLETED,
}
