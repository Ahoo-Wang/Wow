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

package me.ahoo.wow.kernel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.reservation.ReservationStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The write kernel: turns one admitted command into at most one committed event stream
 * (documentation/designs/v10/02-write-path.md §1).
 *
 * 1. **Deduplicate before deciding** (D46): a request id already committed in the tenant is never decided again; a
 *    different command reusing it is rejected.
 * 2. **Load** the committed state, **decide** (the command's function, then its after hooks, D39), **evolve** a
 *    fresh state with the events, **append** once.
 * 3. A version conflict decides again on the newer state, up to [maxAttempts]; an unknown append outcome is settled
 *    by reading the version slot.
 *
 * An attempt whose events are surely not appended (rejected, conflicting, failing) runs the compensations its
 * decide functions registered ([DecideScope.onAppendFailure]); a committed one runs their after-append actions. Once
 * an append commits, the rest of the attempt does not observe cancellation: a caller giving up must not tear what is
 * already a fact.
 *
 * [verifyDecidePurity] makes the kernel fail a command whose decide function or after hook changed the state: with
 * mutable state nothing else stops that, and a change that bypasses events silently drifts from the log (F4). The
 * test DSL turns it on; it costs two serializations of the state per command.
 *
 * [states] keeps states between commands; see [StateCache] for when a state is kept or dropped.
 *
 * [reservations] backs [DecideScope.reserve]; a pending reservation lives for [reservationTtl] unless the append
 * confirms it first.
 */
class Kernel(
    private val log: EventLog,
    private val codec: EventCodec = EventCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val verifyDecidePurity: Boolean = false,
    private val reservations: ReservationStore? = null,
    private val reservationTtl: Duration = DEFAULT_RESERVATION_TTL,
    private val states: StateCache = StateCache.None,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt." }
    }

    suspend fun <S : Any> execute(model: AggregateModel<S>, command: Command<*>): CommandOutcome {
        require(command.ref.type == model.type) { "${command.ref} is not a ${model.type}." }
        val decider = model.decider(command.body.javaClass)
            ?: return rejected(KernelRejections.UNHANDLED, "${model.type} does not handle ${command.body.javaClass.name}.")
        val fingerprint = codec.fingerprint(command.body)
        log.findByRequestId(command.ref.tenantId, command.requestId)?.let { return settled(it, fingerprint) }
        repeat(maxAttempts) {
            val (state, version) = load(model, command)
            admissible(decider, command, version)?.let {
                states.put(command.ref, CachedState(state, version))
                return it
            }
            val scope = AttemptScope(command, reservations, reservationTtl)
            val prepared = try {
                prepare(model, decider, scope, state, command, version, fingerprint)
            } catch (
                @Suppress("TooGenericExceptionCaught") // Compensates whatever failed, then rethrows it unchanged.
                failure: Throwable,
            ) {
                scope.compensate(failure)
                throw failure
            }
            val (stream, evolved) = when (prepared) {
                is Prepared.Done -> {
                    // Decided without evolving: the state is as loaded (decide purity is the user's contract, F4).
                    states.put(command.ref, CachedState(state, version))
                    scope.compensate()
                    return prepared.outcome
                }

                is Prepared.Append -> prepared.stream to prepared.evolved
            }
            when (val appended = withContext(NonCancellable) { append(stream) }) {
                is AppendOutcome.Committed -> {
                    states.put(command.ref, CachedState(evolved, stream.version))
                    withContext(NonCancellable) { scope.afterAppend() }
                    return CommandOutcome.Committed(stream, appended.position, evolved, scope.results())
                }

                is AppendOutcome.AlreadyCommitted -> {
                    scope.compensate()
                    return settled(appended.existing, fingerprint)
                }

                is AppendOutcome.Conflict -> scope.compensate()
                is AppendOutcome.Unknown -> return CommandOutcome.Unknown(appended.cause)
            }
        }
        return CommandOutcome.Contended(maxAttempts)
    }

    /** Decides, runs the after hooks and evolves: either an outcome needing no append, or the stream to append. */
    @Suppress("LongParameterList")
    private suspend fun <S : Any> prepare(
        model: AggregateModel<S>,
        decider: DecideFunction<S>,
        scope: AttemptScope,
        state: S,
        command: Command<*>,
        version: Int,
        fingerprint: String,
    ): Prepared {
        val before = if (verifyDecidePurity) codec.stateText(state) else null
        val decided = when (val decision = decide(decider, scope, state, command.body)) {
            is Decision.Events -> decision.events
            Decision.NoOp -> emptyList()
            is Decision.Rejected -> return Prepared.Done(CommandOutcome.Rejected(decision.rejection))
        }
        val events = decided + model.afterFunctions(command.body.javaClass).flatMap { after(it, scope, state, command.body) }
        if (before != null) {
            check(codec.stateText(state) == before) {
                "Deciding ${command.body.javaClass.name} changed the state of ${command.ref}: only events may change it."
            }
        }
        if (events.isEmpty()) {
            return Prepared.Done(CommandOutcome.Unchanged(version))
        }
        val evolved = events.fold(state) { current, event -> model.evolve(current, event) }
        val stream = EventStreamRecord(
            ref = command.ref,
            version = version + 1,
            commandId = command.id,
            requestId = command.requestId,
            fingerprint = fingerprint,
            commitTime = clock(),
            events = events.map { codec.encode(model, it) },
        )
        return Prepared.Append(stream, evolved)
    }

    /**
     * Appends [stream]; when the outcome is unknown, reads the version slot it targeted, which only one stream can
     * ever hold (the 9.3 rule): ours means committed, another means a conflict, none stays unknown.
     */
    private suspend fun append(stream: EventStreamRecord): AppendOutcome {
        val outcome = log.append(stream)
        if (outcome !is AppendOutcome.Unknown) {
            return outcome
        }
        val slot = try {
            log.read(stream.ref, stream.version, stream.version).firstOrNull()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever stops the read, the outcome stays unknown.
            failure: Exception,
        ) {
            outcome.cause.addSuppressed(failure)
            return outcome
        }
        return when {
            slot == null -> outcome
            slot.requestId == stream.requestId -> AppendOutcome.AlreadyCommitted(slot)
            else -> AppendOutcome.Conflict(stream.version)
        }
    }

    private suspend fun <S : Any> load(model: AggregateModel<S>, command: Command<*>): Pair<S, Int> {
        states.take(command.ref)?.let { cached ->
            @Suppress("UNCHECKED_CAST") // The ref names the aggregate type, so the cached state is this model's.
            return cached.state as S to cached.version
        }
        val streams = log.read(command.ref).toList()
        var state = model.initialState(command.ref)
        streams.forEach { stream ->
            stream.events.forEach { record -> state = model.evolve(state, codec.decode(model, record)) }
        }
        return state to (streams.lastOrNull()?.version ?: 0)
    }

    private fun admissible(decider: DecideFunction<*>, command: Command<*>, version: Int): CommandOutcome? = when {
        version == 0 && decider.creation == Creation.NONE ->
            rejected(KernelRejections.NOT_FOUND, "${command.ref} does not exist.")

        version > 0 && decider.creation == Creation.REQUIRED ->
            rejected(KernelRejections.EXISTS, "${command.ref} already exists.")

        command.expectedVersion != null && command.expectedVersion != version ->
            rejected(
                KernelRejections.VERSION_MISMATCH,
                "${command.ref} is at version $version, not ${command.expectedVersion}.",
            )

        else -> null
    }

    private suspend fun <S : Any> decide(
        decider: DecideFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): Decision = when (decider) {
        is DecideFunction.Sync -> decider.decide(scope, state, body)
        is DecideFunction.Suspending -> decider.decide(scope, state, body)
    }

    private suspend fun <S : Any> after(
        after: AfterFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): List<Any> = when (after) {
        is AfterFunction.Sync -> after.after(scope, state, body)
        is AfterFunction.Suspending -> after.after(scope, state, body)
    }

    private fun settled(existing: EventStreamRecord, fingerprint: String): CommandOutcome =
        if (existing.fingerprint == fingerprint) {
            CommandOutcome.AlreadyCommitted(existing)
        } else {
            rejected(
                KernelRejections.REQUEST_ID_REUSED,
                "Request ${existing.requestId} was already used by another command.",
            )
        }

    private fun rejected(code: String, message: String) = CommandOutcome.Rejected(Rejection(code, message))

    private sealed interface Prepared {
        class Done(val outcome: CommandOutcome) : Prepared

        class Append(val stream: EventStreamRecord, val evolved: Any) : Prepared
    }

    /** One attempt's registrations. Allocates its lists only when a decide function registers something. */
    private class AttemptScope(
        override val command: Command<*>,
        private val reservations: ReservationStore?,
        private val reservationTtl: Duration,
    ) : DecideScope {
        private var compensations: MutableList<suspend () -> Unit>? = null
        private var afterAppendActions: MutableList<suspend () -> Unit>? = null
        private var results: MutableMap<String, Any>? = null

        override fun putResult(key: String, value: Any) {
            (results ?: LinkedHashMap<String, Any>().also { results = it })[key] = value
        }

        fun results(): Map<String, Any> = results ?: emptyMap()

        override suspend fun reserve(namespace: String, key: String): Boolean {
            val store = checkNotNull(reservations) { "Reserving a key needs a kernel with a ReservationStore." }
            // The aggregate owns the key, so a saga can release or move it. Commands of one aggregate are serialized by
            // its partition owner, so two attempts sharing a pending reservation do not overlap in practice.
            val owner = command.ref.toString()
            val reservation = store.reserve(namespace, key, owner, reservationTtl) ?: return false
            if (!reservation.confirmed) {
                onAppendFailure { store.release(namespace, key, owner) }
                afterAppend { store.confirm(namespace, key, owner) }
            }
            return true
        }

        override fun onAppendFailure(compensation: suspend () -> Unit) {
            (compensations ?: ArrayList<suspend () -> Unit>().also { compensations = it }) += compensation
        }

        override fun afterAppend(action: suspend () -> Unit) {
            (afterAppendActions ?: ArrayList<suspend () -> Unit>().also { afterAppendActions = it }) += action
        }

        /** Runs the compensations, latest first; their failures join [cause] when there is one, else they are logged. */
        @Suppress("TooGenericExceptionCaught")
        suspend fun compensate(cause: Throwable? = null) {
            compensations?.asReversed()?.forEach { compensation ->
                try {
                    compensation()
                } catch (failure: Throwable) {
                    cause?.addSuppressed(failure) ?: log.warn(failure) { "A compensation failed." }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        suspend fun afterAppend() {
            afterAppendActions?.forEach { action ->
                try {
                    action()
                } catch (failure: Throwable) {
                    log.warn(failure) { "An after-append action failed; the events are committed." }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        val DEFAULT_RESERVATION_TTL = 30.seconds
        val log = KotlinLogging.logger {}
    }
}
