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

@file:OptIn(me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class)

package me.ahoo.wow.query.internal.cursor

import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import reactor.core.publisher.Mono
import java.time.Instant

/** Coordinates the persistent CAS lease store with backend-owned resource cleanup. */
internal class PersistentQueryCursorLeaseCoordinator(
    private val manager: PersistentQueryCursorLeaseManager,
    registrations: Iterable<PersistentQueryCursorBackendLeaseRegistration>,
    private val observer: QueryCursorLeaseObserver = QueryCursorLeaseObserver.NOOP,
) {
    private val closers: Map<CursorBackendKey, QueryCursorBackendLeaseCloser> = registrations.toList().let { values ->
        require(
            values.map { registration -> registration.target to registration.backendId }.distinct().size == values.size,
        ) {
            "Duplicate cursor backend lease registration."
        }
        values.associate { registration ->
            CursorBackendKey(registration.target, registration.backendId) to registration.closer
        }
    }

    fun load(token: QueryCursorToken): Mono<LoadedQueryCursorLease> = manager.load(token)

    fun acquire(
        loaded: LoadedQueryCursorLease,
        expectedBinding: QueryCursorLeaseBinding,
    ): Mono<QueryCursorEnvelope> = manager.acquire(loaded, expectedBinding)

    fun issue(envelope: QueryCursorEnvelope): Mono<QueryCursorToken> {
        requireCloser(envelope)
        return manager.issue(envelope)
    }

    fun supports(target: QueryTarget, backendId: BackendId): Boolean =
        closers.containsKey(CursorBackendKey(target, backendId))

    fun close(envelope: QueryCursorEnvelope, reason: QueryCursorCleanupReason): Mono<Void> =
        closeBestEffort(envelope, reason)

    fun close(
        state: QueryCursorBackendState,
        descriptor: QueryCursorLeaseDescriptor,
        reason: QueryCursorCleanupReason,
    ): Mono<Void> = closeBestEffort(state, descriptor, reason)

    /** Reaps one bounded, stable-keyset batch. Scheduling and repetition remain an application responsibility. */
    fun reapExpired(before: Instant, batchSize: Int): Mono<Long> {
        require(batchSize > 0) { "Query cursor reaper batch size must be positive." }
        return manager.reapExpired(before, limit = batchSize)
            .concatMap { envelope ->
                closeForReaper(envelope).map { closed -> if (closed) 1L else 0L }
            }
            .reduce(0L, Long::plus)
    }

    private fun closeForReaper(envelope: QueryCursorEnvelope): Mono<Boolean> {
        val state = envelope.backendState ?: return Mono.just(true)
        val descriptor = QueryCursorLeaseDescriptor(envelope.target, state.backendId, envelope.mappingGenerationDigest)
        val closer = closers[CursorBackendKey(descriptor.target, state.backendId)] ?: return Mono.fromSupplier {
            notifyFailure(
                descriptor,
                QueryCursorCleanupReason.ABANDONED,
                IllegalStateException("Cursor backend closer is not registered."),
            )
            false
        }
        return Mono.defer { closer.close(state) }
            .thenReturn(true)
            .onErrorResume { error ->
                notifyFailure(descriptor, QueryCursorCleanupReason.ABANDONED, error)
                Mono.just(false)
            }
    }

    private fun requireCloser(envelope: QueryCursorEnvelope) {
        val state = envelope.backendState ?: return
        if (closers[CursorBackendKey(envelope.target, state.backendId)] == null) {
            rejectQuery(
                QueryRejectionCategory.BACKEND_UNAVAILABLE,
                CURSOR_PATH,
                QueryRejectionCode.BACKEND_NOT_REGISTERED,
            )
        }
    }

    private fun closeBestEffort(
        envelope: QueryCursorEnvelope,
        reason: QueryCursorCleanupReason,
    ): Mono<Void> = envelope.backendState?.let { state ->
        closeBestEffort(
            state,
            QueryCursorLeaseDescriptor(envelope.target, state.backendId, envelope.mappingGenerationDigest),
            reason,
        )
    } ?: Mono.empty()

    private fun closeBestEffort(
        state: QueryCursorBackendState,
        descriptor: QueryCursorLeaseDescriptor,
        reason: QueryCursorCleanupReason,
    ): Mono<Void> {
        val closer = closers[CursorBackendKey(descriptor.target, state.backendId)] ?: return Mono.fromRunnable {
            notifyFailure(descriptor, reason, IllegalStateException("Cursor backend closer is not registered."))
        }
        return Mono.defer { closer.close(state) }
            .onErrorResume { error ->
                notifyFailure(descriptor, reason, error)
                Mono.empty()
            }
    }

    private fun notifyFailure(
        descriptor: QueryCursorLeaseDescriptor,
        reason: QueryCursorCleanupReason,
        error: Throwable,
    ) {
        try {
            observer.onCleanupFailure(descriptor, reason, error)
        } catch (_: RuntimeException) {
            // Observability must never replace the query or cleanup outcome.
        }
    }

    private companion object {
        val CURSOR_PATH: QueryRejectionPath = QueryRejectionPath.ROOT.property("cursor")
    }

    private data class CursorBackendKey(val target: QueryTarget, val backendId: BackendId)
}

internal data class PersistentQueryCursorBackendLeaseRegistration(
    val target: QueryTarget,
    val backendId: BackendId,
    val closer: QueryCursorBackendLeaseCloser,
)
