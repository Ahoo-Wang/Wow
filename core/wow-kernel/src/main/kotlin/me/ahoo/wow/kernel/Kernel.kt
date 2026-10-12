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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import tools.jackson.core.JacksonException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The write kernel: turns one admitted command into at most one committed event stream
 * (documentation/designs/v10/02-write-path.md §1).
 *
 * 1. **Deduplicate before deciding** (D46): a request id already committed in the tenant is never decided again; a
 *    command of another type reusing it is rejected. Commands are told apart by their model name only, not by a hash
 *    of their body (D55): a same-type command reusing a request id is taken for a retry, by the caller's contract.
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
 * [states] keeps states between commands; see [StateCache] for when a state is kept or dropped. On a miss, the state
 * comes from the aggregate's snapshot in [snapshots] and the log's tail after it when the snapshot fits the model's
 * state schema (03-log-and-snapshot.md §3), else from replaying the whole log. A snapshot is only a cache: one that
 * does not fit, or a store that fails, is logged and costs a full replay, never the command.
 *
 * [reservations] backs [DecideScope.reserve]; a pending reservation lives for [reservationTtl] unless the append
 * confirms it first. The keys an attempt reserved pending go into the appended entry's transport (D50), so the
 * `reservation` subscription confirms them even when the after-append confirmation never runs.
 *
 * Every stream carries the aggregate's scope after it (D56): the creating command's scope, carried forward unchanged
 * by every later command (see [scopeAfter]).
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
    private val snapshots: SnapshotStore? = null,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt." }
    }

    suspend fun <S : Any> execute(model: AggregateModel<S>, command: Command<*>): CommandOutcome {
        require(command.ref.type == model.type) { "${command.ref} is not a ${model.type}." }
        val decider = model.decider(command.body.javaClass)
            ?: return rejected(KernelRejections.UNHANDLED, "${model.type} does not handle ${command.body.javaClass.name}.")
        val commandType = model.commandType(command.body.javaClass).name
        log.findByRequestId(command.ref.tenantId, command.requestId)?.let { return settled(it, commandType) }
        repeat(maxAttempts) {
            val loaded = load(model, command)
            admissible(decider, command, loaded.version)?.let {
                states.put(command.ref, loaded)
                return it
            }
            val attempt = AttemptScope(command, reservations, reservationTtl)
            val prepared = try {
                prepare(model, decider, attempt, loaded, command, commandType)
            } catch (
                @Suppress("TooGenericExceptionCaught") // Compensates whatever failed, then rethrows it unchanged.
                failure: Throwable,
            ) {
                attempt.compensate(failure)
                throw failure
            }
            val (stream, evolved) = when (prepared) {
                is Prepared.Done -> {
                    // Decided without evolving: the state is as loaded (decide purity is the user's contract, F4).
                    states.put(command.ref, loaded)
                    attempt.compensate()
                    return prepared.outcome
                }

                is Prepared.Append -> prepared.stream to prepared.evolved
            }
            when (val appended = withContext(NonCancellable) { append(stream, attempt.transport()) }) {
                is AppendOutcome.Committed -> {
                    states.put(command.ref, CachedState(evolved, stream.version, stream.scope))
                    withContext(NonCancellable) { attempt.afterAppend() }
                    return CommandOutcome.Committed(stream, appended.position, evolved, attempt.results())
                }

                is AppendOutcome.AlreadyCommitted -> {
                    attempt.compensate()
                    return settled(appended.existing, commandType)
                }

                is AppendOutcome.Conflict -> attempt.compensate()
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
        loaded: CachedState,
        command: Command<*>,
        commandType: String,
    ): Prepared {
        @Suppress("UNCHECKED_CAST") // The ref names the aggregate type, so the loaded state is this model's.
        val state = loaded.state as S
        val version = loaded.version
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
            commandType = commandType,
            commitTime = clock(),
            events = events.map { codec.encode(model, it) },
            scope = scopeAfter(loaded, command),
            causation = command.causation,
            headers = command.headers,
        )
        return Prepared.Append(stream, evolved)
    }

    /**
     * The aggregate's scope after [command] commits on [loaded] (decision D56): a command that creates the aggregate
     * gives it the caller's scope; every other command carries the aggregate's scope forward unchanged.
     *
     * This is the seam for the lifecycle commands that change it, transferring the owner or the space (design 01 §6,
     * roadmap 2.4): they will decide the new scope here, from their own command type, and nothing else will.
     */
    private fun scopeAfter(loaded: CachedState, command: Command<*>): Scope =
        if (loaded.version == 0) command.scope else loaded.scope

    /**
     * Appends [stream] with [transport]; when the outcome is unknown, reads the version slot it targeted, which only
     * one stream can ever hold (the 9.3 rule): ours means committed, another means a conflict, none stays unknown.
     */
    private suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
        val outcome = log.append(stream, transport)
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

    /** The aggregate's committed state, version and scope: cached, else from its snapshot and tail, else replayed. */
    private suspend fun <S : Any> load(model: AggregateModel<S>, command: Command<*>): CachedState {
        states.take(command.ref)?.let { return it }
        loadSnapshot(command.ref)?.let { snapshot -> fromSnapshot(model, snapshot)?.let { return it } }
        return replay(model, CachedState(model.initialState(command.ref), 0), log.read(command.ref).toList())
    }

    /**
     * The snapshot of [ref], or null when there is none or the store fails: a snapshot is only a cache, so a failing
     * store costs a full replay, never the command. Cancellation still propagates.
     */
    private suspend fun loadSnapshot(ref: AggregateRef): SnapshotRecord? {
        val store = snapshots ?: return null
        return try {
            store.load(ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever the store throws, the log still holds the state.
            failure: Exception,
        ) {
            logger.warn(failure) { "Loading the snapshot of $ref failed; replaying its log." }
            null
        }
    }

    /**
     * The state of [snapshot] evolved with the log's tail after it, or null when the snapshot does not fit: written
     * for another state schema, not decodable, or newer than the log. The tail is read from the snapshot's own
     * version, so the same read proves the log holds that version; that stream is already folded and is skipped.
     */
    private suspend fun <S : Any> fromSnapshot(model: AggregateModel<S>, snapshot: SnapshotRecord): CachedState? {
        if (snapshot.schemaFingerprint != codec.stateSchemaFingerprint(model)) {
            logger.debug { "The snapshot of ${snapshot.ref} has another state schema; replaying its log." }
            return null
        }
        val state = try {
            codec.decodeState(model, snapshot.state)
        } catch (failure: JacksonException) {
            logger.warn(failure) { "The snapshot of ${snapshot.ref} does not decode; replaying its log." }
            return null
        } catch (failure: IllegalArgumentException) {
            logger.warn(failure) { "The snapshot of ${snapshot.ref} does not decode whole; replaying its log." }
            return null
        }
        val streams = log.read(snapshot.ref, snapshot.version).toList()
        if (streams.firstOrNull()?.version != snapshot.version) {
            logger.warn { "The snapshot of ${snapshot.ref} is at version ${snapshot.version}, beyond its log; replaying it." }
            return null
        }
        return replay(model, CachedState(state, snapshot.version, snapshot.scope), streams.subList(1, streams.size))
    }

    /**
     * Evolves [initial] with the events of [streams]; the version and the scope are the last stream's, or
     * [initial]'s when there is none.
     */
    private fun <S : Any> replay(
        model: AggregateModel<S>,
        initial: CachedState,
        streams: List<EventStreamRecord>,
    ): CachedState {
        @Suppress("UNCHECKED_CAST") // The initial state is built by this model, or read as its state type.
        var state = initial.state as S
        streams.forEach { stream ->
            stream.events.forEach { record -> state = model.evolve(state, codec.decode(model, record)) }
        }
        val last = streams.lastOrNull() ?: return CachedState(state, initial.version, initial.scope)
        return CachedState(state, last.version, last.scope)
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

    private fun settled(existing: EventStreamRecord, commandType: String): CommandOutcome =
        if (existing.commandType == commandType) {
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
        private var reserved: MutableList<ReservationKey>? = null

        override fun putResult(key: String, value: Any) {
            (results ?: LinkedHashMap<String, Any>().also { results = it })[key] = value
        }

        fun results(): Map<String, Any> = results ?: emptyMap()

        /** What the append carries for delivery: the keys this attempt reserved pending, if any (D50). */
        fun transport(): Transport? = reserved?.let { Transport(reservations = it.toList()) }

        override suspend fun reserve(namespace: String, key: String): Boolean {
            val store = checkNotNull(reservations) { "Reserving a key needs a kernel with a ReservationStore." }
            // The aggregate owns the key, so a saga can release or move it. Commands of one aggregate are serialized by
            // its partition owner, so two attempts sharing a pending reservation do not overlap in practice.
            val owner = command.ref.toString()
            val reservation = store.reserve(namespace, key, owner, reservationTtl) ?: return false
            if (!reservation.confirmed) {
                (reserved ?: ArrayList<ReservationKey>().also { reserved = it }) += ReservationKey(namespace, key)
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
                    cause?.addSuppressed(failure) ?: logger.warn(failure) { "A compensation failed." }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        suspend fun afterAppend() {
            afterAppendActions?.forEach { action ->
                try {
                    action()
                } catch (failure: Throwable) {
                    logger.warn(failure) { "An after-append action failed; the events are committed." }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        val DEFAULT_RESERVATION_TTL = 30.seconds
        val logger = KotlinLogging.logger {}
    }
}
