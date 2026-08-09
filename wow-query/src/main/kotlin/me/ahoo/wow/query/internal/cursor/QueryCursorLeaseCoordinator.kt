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

package me.ahoo.wow.query.internal.cursor

import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal fun interface QueryCursorBackendLeaseCloser {
    fun close(state: QueryCursorBackendState): Mono<Void>
}

internal data class QueryCursorBackendLeaseRegistration(
    val backendId: BackendId,
    val closer: QueryCursorBackendLeaseCloser,
)

internal enum class QueryCursorCleanupReason {
    TERMINAL,
    ABANDONED,
}

internal data class QueryCursorLeaseDescriptor(
    val target: QueryTarget,
    val backendId: BackendId,
    val mappingGenerationDigest: QueryCursorMappingDigest,
)

internal fun interface QueryCursorLeaseObserver {
    fun onCleanupFailure(
        descriptor: QueryCursorLeaseDescriptor,
        reason: QueryCursorCleanupReason,
        error: Throwable,
    )

    companion object {
        val NOOP: QueryCursorLeaseObserver = QueryCursorLeaseObserver { _, _, _ -> }
    }
}

/** Coordinates one-time cursor ownership with backend resource cleanup. */
internal class QueryCursorLeaseCoordinator(
    private val manager: InMemoryQueryCursorLeaseManager,
    registrations: Iterable<QueryCursorBackendLeaseRegistration>,
    private val observer: QueryCursorLeaseObserver = QueryCursorLeaseObserver.NOOP,
) {
    private val closers: Map<BackendId, QueryCursorBackendLeaseCloser> = registrations.toList().let { values ->
        require(values.map(QueryCursorBackendLeaseRegistration::backendId).distinct().size == values.size) {
            "Duplicate cursor backend lease registration."
        }
        values.associate { registration -> registration.backendId to registration.closer }
    }

    fun issue(envelope: QueryCursorEnvelope): QueryCursorToken {
        requireCloser(envelope)
        return manager.issue(envelope)
    }

    fun acquire(token: QueryCursorToken, expectedBinding: QueryCursorLeaseBinding): AcquiredQueryCursorLease =
        AcquiredQueryCursorLease(this, manager.acquire(token, expectedBinding))

    fun reapExpired(): Mono<Void> = Flux.fromIterable(manager.reapExpired())
        .flatMapSequential(
            { envelope -> closeBestEffort(envelope, QueryCursorCleanupReason.ABANDONED) },
            CLEANUP_CONCURRENCY,
        ).then()

    internal fun transfer(
        current: QueryCursorEnvelope,
        position: QueryCursorPosition,
        expiresAt: java.time.Instant,
        backendState: QueryCursorBackendState?,
    ): QueryCursorToken {
        if (current.backendState?.backendId != backendState?.backendId) {
            rejectQuery(
                QueryRejectionCategory.INVALID_CURSOR,
                CURSOR_PATH,
                QueryRejectionCode.INVALID_CURSOR_BINDING,
            )
        }
        return issue(current.copy(position = position, expiresAt = expiresAt, backendState = backendState))
    }

    internal fun closeTerminal(envelope: QueryCursorEnvelope): Mono<Void> =
        closeBestEffort(envelope, QueryCursorCleanupReason.TERMINAL)

    private fun requireCloser(envelope: QueryCursorEnvelope) {
        val state = envelope.backendState ?: return
        if (closers[state.backendId] == null) {
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
    ): Mono<Void> {
        val state = envelope.backendState ?: return Mono.empty()
        val closer = closers[state.backendId] ?: return Mono.fromRunnable {
            notifyFailure(envelope, reason, IllegalStateException("Cursor backend closer is not registered."))
        }
        return Mono.defer { closer.close(state) }
            .onErrorResume { error ->
                notifyFailure(envelope, reason, error)
                Mono.empty()
            }
    }

    private fun notifyFailure(
        envelope: QueryCursorEnvelope,
        reason: QueryCursorCleanupReason,
        error: Throwable,
    ) {
        val state = envelope.backendState ?: return
        try {
            observer.onCleanupFailure(
                QueryCursorLeaseDescriptor(envelope.target, state.backendId, envelope.mappingGenerationDigest),
                reason,
                error,
            )
        } catch (_: RuntimeException) {
            // Observability must never replace the query or cleanup outcome.
        }
    }

    private companion object {
        const val CLEANUP_CONCURRENCY = 8
        val CURSOR_PATH: QueryRejectionPath = QueryRejectionPath.ROOT.property("cursor")
    }
}

internal class AcquiredQueryCursorLease internal constructor(
    private val coordinator: QueryCursorLeaseCoordinator,
    val envelope: QueryCursorEnvelope,
) {
    private var settled = false

    @Synchronized
    fun transfer(
        position: QueryCursorPosition,
        expiresAt: java.time.Instant,
        backendState: QueryCursorBackendState? = envelope.backendState,
    ): QueryCursorToken {
        check(!settled) { "Cursor lease has already been settled." }
        val token = coordinator.transfer(envelope, position, expiresAt, backendState)
        settled = true
        return token
    }

    fun close(): Mono<Void> = Mono.defer {
        val shouldClose = synchronized(this) {
            if (settled) {
                false
            } else {
                settled = true
                true
            }
        }
        if (shouldClose) coordinator.closeTerminal(envelope) else Mono.empty()
    }
}
