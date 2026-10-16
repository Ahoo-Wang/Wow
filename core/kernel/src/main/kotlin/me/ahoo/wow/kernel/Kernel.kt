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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.wait.WaitContext
import tools.jackson.core.JacksonException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The write kernel: turns one admitted command into at most one committed event stream
 * (documentation/designs/v10/02-write-path.md §1).
 *
 * 1. **Deduplicate before deciding** (D46): a request id already committed in the tenant is never decided again; a
 *    command of another type reusing it is rejected. Commands are told apart by their model name only, not by a hash
 *    of their body (D55): a same-type command reusing a request id is taken for a retry, by the caller's contract.
 *    A duplicate is answered only within the scope that sent it (see [duplicate]).
 * 2. **Load** the committed state, **decide** (the command's function, then its after hooks, D39), **evolve** a
 *    fresh state with the events, **append** once.
 * 3. A version conflict decides again on the newer state, up to [maxAttempts]; an unknown append outcome is settled
 *    by reading the version slot.
 * 4. A rejection or an unchanged outcome decided on a cached state stands only once the log holds nothing after the
 *    state's version: one read of the next version slot, else the state is reloaded and the command decided again
 *    (not counted as an attempt). Another node of the service may have appended (decisions D45, D71), and only an
 *    append meets the version-unique key.
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
 * [reservations] backs [DecideScope.reserve] and [DecideScope.release]; a pending reservation lives for
 * [reservationTtl] unless the append confirms it first. Each attempt reserves by a hold of its own, so an attempt
 * surely not appended abandons only its own hold (finding A1-2). The keys an attempt reserved pending, and those it
 * released, go into the appended entry's transport (D50), so the `reservation` subscription confirms and releases
 * them even when the after-append actions never run.
 *
 * Every stream carries the aggregate's scope after it (D56): the creating command's scope, carried forward by every
 * later command and changed only by the lifecycle commands transferring the owner or the space (see [metadataAfter]).
 * The kernel keeps the deleted mark and the tags beside the state too, evolved by the lifecycle events: a deleted
 * aggregate refuses every command but [RestoreAggregate] ([KernelRejections.DELETED]). The keys of the tags the state
 * derives ([AggregateModel.tagsOf], `@Tags`, decision D220) override the applied ones; the kernel derives them after
 * each stream it evolves, decides or commits, and on every state it loads from a snapshot or is handed, so the tags
 * of every state it gives out (a commit's, a snapshot's, a trace's, a state event's) follow the state.
 *
 * The aggregate's scope also guards it (D94): an existing aggregate refuses a command whose scope names another owner
 * or space ([KernelRejections.FORBIDDEN]). A part absent from the command's scope is not checked: a tenant-level
 * caller's (decision D189), or an administrator's acting across scopes, carries no owner. Creating an aggregate is not
 * guarded; it gives the aggregate the creating command's scope. An aggregate that belongs to its tenant
 * ([AggregateModel.ownership], decision D189) neither records nor checks an owner: only the space of a command counts.
 * A transfer of the owner or the space is guarded like any other command: its scope is the caller's, so the current
 * owner (or a member of the current space) transfers, and the new owner or space is in its body.
 *
 * A derived command ([isDerived]: a saga's, an `@OnRejected` compensation, a timer's, one another context sent
 * through its command topic) is not guarded (decision D199): the server's own code made it, there is no caller to
 * check, and what it may do to whom is the domain's to decide, such as A's transfer crediting B's account. The root
 * command was authorized when it was admitted. The scope it inherits from the root only gives an aggregate it creates
 * its ownership (D189) and is recorded for the audit; a retry of one is answered whatever the aggregate's scope.
 *
 * A decide function's schedules ([DecideScope.schedule], [DecideScope.cancelSchedule], decisions D166, D198) are the
 * system events `wow.ScheduleSet` and `wow.ScheduleCancelled` after its events in the stream it commits, the last
 * change of each key: they commit with its events, or not at all, and a decision that only schedules commits them
 * alone (on an aggregate that exists). The command a schedule sends is stored by its type name and JSON:
 * [commandTypes] names a command of an aggregate type other than the deciding one. Every aggregate also decides the
 * system commands `wow.schedule.Set` and `wow.schedule.Cancel` a saga derives ([KernelLifecycle]); the kernel keeps
 * the rules of the schedules an aggregate holds beside its state ([CachedState.schedules]).
 *
 * A command a schedule sent ([Command.schedule]) is not decided before the time its slot was due by this kernel's
 * [clock] (decision D198): a node whose clock is behind the firing node's waits until then, so a schedule never fires
 * early. It waits at most [maxEarlyWait], holding the aggregate's queue meanwhile: a command due later than that fails
 * at once with [ScheduleNotDueException], and its firing runner sends it again later.
 *
 * A decide function takes its ids from [ids] ([DecideScope.ids], decision D171): the node's generators, fixed ids in
 * the test DSL.
 *
 * A decide function or after hook marked `@Blocking` ([DecideFunction.Suspending.blocking], decision D227) runs on
 * [blocking], by default the node's virtual threads ([BlockingDispatcher]); every other one runs inline in the
 * caller's coroutine.
 *
 * [observer] sees each execution, decision and append (decision D174); what an append carries for delivery holds the
 * append's trace ([Transport.trace]), so the subscriptions that handle it continue the command's trace.
 */
@InternalWowApi
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
    private val commandTypes: CommandTypes = CommandTypes.NONE,
    private val ids: Ids = IdGenerators.DEFAULT,
    private val maxEarlyWait: Duration = DEFAULT_MAX_EARLY_WAIT,
    private val observer: Observer = Observer.NONE,
    private val blocking: CoroutineContext = BlockingDispatcher.dispatcher,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt." }
        require(!maxEarlyWait.isNegative()) { "The maximum early wait is never negative." }
    }

    /**
     * Executes [admitted] on [model]'s aggregate, its scope narrowed to what the model's ownership keeps
     * ([me.ahoo.wow.api.aggregate.Ownership.scopeOf], decision D189).
     *
     * [wait] is the wait context the runtime built for the command's wait plan, if any: when it still has hops to
     * reach after COMMITTED, it is appended with the stream as transport (decision D25), so the subscription its next
     * hop names signals the waiting node. The kernel only carries it; registering the wait and the signals are the
     * runtime's.
     */
    suspend fun <S : Any> execute(
        model: AggregateModel<S>,
        admitted: Command<*>,
        wait: WaitContext? = null,
    ): CommandOutcome {
        require(admitted.ref.type == model.type) { "${admitted.ref} is not a ${model.type}." }
        require(admitted.ref.isAssigned) { "${admitted.ref} names no aggregate: admission assigns its id (D191)." }
        val executing = observer.executing(admitted)
        var committed: CommandOutcome.Committed? = null
        return try {
            executing.observe {
                executeAdmitted(model, admitted, wait).also {
                    committed = it as? CommandOutcome.Committed
                    executing.outcome(it.observedName)
                }
            }
        } catch (cancelled: CancellationException) {
            // An observation's context returns through a cancellable switch (finding A1-11): a commit that switch
            // dropped for a caller cancelled meanwhile is still answered, so its state view is recorded.
            committed ?: throw cancelled
        }
    }

    private suspend fun <S : Any> executeAdmitted(
        model: AggregateModel<S>,
        admitted: Command<*>,
        wait: WaitContext?,
    ): CommandOutcome {
        // A tenant's aggregate (decision D189) neither records nor checks an owner: only that part of the scope counts.
        val scope = model.ownership.scopeOf(admitted.scope)
        val command = if (scope == admitted.scope) admitted else admitted.copyWith(scope = scope)
        val decider = model.decider(command.body.javaClass)
            ?: return rejected(KernelRejections.UNHANDLED, "${model.type} does not handle ${command.body.javaClass.name}.")
        val commandType = model.commandType(command.body.javaClass)
        // The request key is unique within an aggregate type (decision D162): one lookup in this type's storage.
        log.findByRequestId(model.type, command.ref.tenantId, command.requestId)
            ?.let { return duplicate(it, command, commandType) }
        awaitDue(command)
        val waitBeyondCommitted = wait?.afterCommitted()?.takeIf { it.remaining.isNotEmpty() }
        val encode = { target: AggregateRef, body: Any -> scheduledCommand(model, target, body) }
        var attempts = 0
        while (attempts < maxAttempts) {
            val (loaded, cached) = load(model, command)
            admissible(decider, command, loaded)?.let { return standing(command.ref, loaded, cached, it) ?: continue }
            val attempt = AttemptScope(command, ids, loaded, reservations, reservationTtl, waitBeyondCommitted, encode)
            val prepared = try {
                prepare(model, decider, attempt, loaded, command, commandType.name)
            } catch (
                @Suppress("TooGenericExceptionCaught") // Compensates whatever failed, then rethrows it unchanged.
                failure: Throwable,
            ) {
                attempt.compensate(failure)
                throw failure
            }
            val (stream, evolved) = when (prepared) {
                is Prepared.Done -> {
                    attempt.compensate()
                    return standing(command.ref, loaded, cached, prepared.outcome) ?: continue
                }

                is Prepared.Append -> prepared.stream to prepared.evolved
            }
            val appended = withContext(NonCancellable) { appendObserved(stream, attempt) }
            settleAppend(appended, stream, evolved, attempt, commandType)?.let { return it }
            attempts++
        }
        return CommandOutcome.Contended(maxAttempts)
    }

    /**
     * Waits until the slot [command] was sent for is due on this kernel's clock, when it was sent for a schedule
     * (decision D198): a schedule never fires before its time here, whatever the firing node's clock said. One due
     * later than [maxEarlyWait] from now is not waited for, holding the aggregate's queue: it throws
     * [ScheduleNotDueException], and its firing runner sends it again later.
     */
    private suspend fun awaitDue(command: Command<*>) {
        val due = (command.schedule ?: return).due
        val now = clock()
        val early = due - now
        if (early > maxEarlyWait.inWholeMilliseconds) {
            throw ScheduleNotDueException(command.ref, command.requestId, due, now)
        }
        if (early > 0) {
            delay(early)
        }
    }

    /** Appends [stream] of [attempt], observed: what it carries for delivery holds the append's trace (D174). */
    private suspend fun appendObserved(stream: EventStreamRecord, attempt: AttemptScope): AppendOutcome {
        val appending = observer.appending(stream)
        return appending.observe {
            append(stream, attempt.transport(appending.trace)).also { appending.outcome(it.observedName) }
        }
    }

    /** The outcome of an attempt whose [stream] was [appended]; null on a version conflict, decided again. */
    private suspend fun settleAppend(
        appended: AppendOutcome,
        stream: EventStreamRecord,
        evolved: CachedState,
        attempt: AttemptScope,
        commandType: CommandType,
    ): CommandOutcome? = when (appended) {
        is AppendOutcome.Committed -> {
            states.put(stream.ref, evolved)
            withContext(NonCancellable) { attempt.afterAppend() }
            CommandOutcome.Committed(
                stream,
                appended.position,
                evolved.state,
                attempt.results(),
                evolved.deleted,
                evolved.tags,
                evolved.schedules,
            )
        }

        is AppendOutcome.AlreadyCommitted -> {
            attempt.compensate()
            settled(appended.existing, commandType)
        }

        is AppendOutcome.Conflict -> {
            attempt.compensate()
            // Decided again only once compensated, and only while the caller still waits.
            currentCoroutineContext().ensureActive()
            null
        }

        is AppendOutcome.Unknown -> CommandOutcome.Unknown(appended.cause)
        is AppendOutcome.Rejected -> {
            // Nothing was committed and nothing will be: compensated, and failed as a refusing backend always failed.
            attempt.compensate()
            throw appended.cause
        }
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
        val domain = when (
            val decision = observer.deciding(command).observe { decideAll(model, decider, scope, state, command.body) }
        ) {
            is Decision.Events -> decision.events
            Decision.NoOp -> emptyList()
            is Decision.Rejected -> return Prepared.Done(CommandOutcome.Rejected(decision.rejection))
        }
        if (before != null) {
            check(codec.stateText(state) == before) {
                "Deciding ${command.body.javaClass.name} changed the state of ${command.ref}: only events may change it."
            }
        }
        val schedules = scope.scheduleEvents()
        val events = domain + schedules
        if (events.isEmpty()) {
            return Prepared.Done(CommandOutcome.Unchanged(version))
        }
        check(domain.isNotEmpty() || version > 0) {
            "${command.ref} does not exist: a decision that only schedules needs an aggregate that exists."
        }
        val commitTime = clock()
        val evolved = events.fold(state) { current, event -> model.evolve(current, event) }
        val applied = metadataAfter(loaded, command, events)
        // The keys the state derives override the applied tags (decision D220), derived again from the evolved state.
        val metadata = applied.copy(tags = model.tagsOf(evolved, applied.tags))
        val stream = EventStreamRecord(
            ref = command.ref,
            version = version + 1,
            commandId = command.id,
            requestId = command.requestId,
            commandType = commandType,
            commitTime = commitTime,
            events = events.map { codec.encode(model, it) },
            scope = metadata.scope,
            causation = command.causation,
            headers = command.headers,
            operator = command.operator,
        )
        return Prepared.Append(stream, loaded.with(evolved, stream.version, metadata))
    }

    /**
     * The type name and JSON of [body], a command [model]'s aggregate schedules for [target] (decision D166): the
     * model names its own commands, [commandTypes] those of other aggregate types.
     */
    private fun scheduledCommand(model: AggregateModel<*>, target: AggregateRef, body: Any): Pair<String, String> {
        val type = if (target.type == model.type) {
            model.commandType(body.javaClass).name
        } else {
            requireNotNull(commandTypes.nameOf(target.type, body.javaClass)) {
                "${model.type} schedules ${body.javaClass.name} for $target, a command no Manifest of the service or " +
                    "its peers names (decision D139)."
            }
        }
        return type to codec.commandText(body)
    }

    /**
     * The aggregate's metadata after [command] commits [events] on [loaded]. Its scope (decision D56): a command that
     * creates the aggregate gives it the caller's scope; every other command carries the aggregate's scope forward,
     * and only the lifecycle events of transferring the owner or the space change it (design 01 §6). Its deleted mark
     * and tags change only by their lifecycle events too.
     */
    private fun metadataAfter(loaded: CachedState, command: Command<*>, events: List<Any>): KernelLifecycle.Metadata {
        val before = if (loaded.version == 0) loaded.metadata.copy(scope = command.scope) else loaded.metadata
        return events.fold(before, KernelLifecycle::evolve)
    }

    /**
     * Appends [stream] with [transport]. An outcome that names this very append (the same command, request and commit
     * time at the same version) is this append committed: when the outcome is unknown, the version slot it targeted,
     * which only one stream can ever hold (the 9.3 rule), decides it (ours means committed, another stream of the
     * request a duplicate, another request a conflict, none stays unknown); and a duplicate the backend reports may be
     * its own retry of this append. Treating either as a duplicate would compensate what is a fact (finding A1-1).
     * A conflict is checked against the request key before the command is decided again (finding A2-1): a backend that
     * compares the version first reports a retry of the request at the same version as a conflict, and deciding it
     * again would run the decision's I/O twice (decision D46).
     */
    private suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        when (val outcome = log.append(stream, transport)) {
            is AppendOutcome.Unknown -> settleUnknown(stream, outcome)
            is AppendOutcome.AlreadyCommitted -> duplicateOf(stream, outcome)
            is AppendOutcome.Conflict -> settleConflict(stream, outcome)
            else -> outcome
        }

    /** [duplicate] of [stream]: [stream] itself committed when it names this very append (finding A1-1). */
    private suspend fun duplicateOf(
        stream: EventStreamRecord,
        duplicate: AppendOutcome.AlreadyCommitted
    ): AppendOutcome =
        if (duplicate.existing.isAppendOf(stream)) committedAt(stream, duplicate) else duplicate

    /**
     * The [conflict] of [stream], unless its request is committed: then a duplicate, or this append itself. A failing
     * lookup leaves the conflict, decided again as before.
     */
    private suspend fun settleConflict(stream: EventStreamRecord, conflict: AppendOutcome.Conflict): AppendOutcome {
        val existing = try {
            log.findByRequestId(stream.ref.type, stream.ref.tenantId, stream.requestId)
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever stops the lookup, the conflict stands.
            failure: Exception,
        ) {
            logger.debug(failure) { "The request of ${stream.ref}@${stream.version} could not be looked up again." }
            null
        }
        return existing?.let { duplicateOf(stream, AppendOutcome.AlreadyCommitted(it)) } ?: conflict
    }

    /** Reads the version slot an [unknown] append of [stream] targeted; a failing read leaves it unknown. */
    private suspend fun settleUnknown(stream: EventStreamRecord, unknown: AppendOutcome.Unknown): AppendOutcome {
        val slot = try {
            log.readEntries(stream.ref, stream.version, stream.version).firstOrNull()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever stops the read, the outcome stays unknown.
            failure: Exception,
        ) {
            unknown.cause.addSuppressed(failure)
            return unknown
        }
        return when {
            slot == null -> unknown
            slot.stream.isAppendOf(stream) -> AppendOutcome.Committed(slot.position)
            slot.stream.requestId == stream.requestId -> AppendOutcome.AlreadyCommitted(slot.stream)
            else -> AppendOutcome.Conflict(stream.version)
        }
    }

    /**
     * [stream], which the backend reported [duplicate] of itself, committed at its position. Committed it is, so it is
     * never compensated: when its position cannot be read, the outcome is unknown, which compensates nothing either.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever stops the read, the commit stands; its outcome is unknown.
    private suspend fun committedAt(
        stream: EventStreamRecord,
        duplicate: AppendOutcome.AlreadyCommitted
    ): AppendOutcome {
        val position = try {
            log.positionOf(stream.ref, stream.version)
        } catch (failure: Exception) {
            return AppendOutcome.Unknown(failure)
        }
        val existing = duplicate.existing
        return position?.let { AppendOutcome.Committed(it) } ?: AppendOutcome.Unknown(
            IllegalStateException("${existing.ref}@${existing.version} is committed without a position."),
        )
    }

    /** Whether this committed stream is the append of [stream] itself, not another append of its request. */
    private fun EventStreamRecord.isAppendOf(stream: EventStreamRecord): Boolean =
        ref == stream.ref &&
            version == stream.version &&
            commandId == stream.commandId &&
            requestId == stream.requestId &&
            commitTime == stream.commitTime

    /**
     * The aggregate's committed state, version and scope: cached, else from its snapshot and tail, else replayed; and
     * whether it came from the cache.
     */
    private suspend fun <S : Any> load(model: AggregateModel<S>, command: Command<*>): Pair<CachedState, Boolean> {
        states.take(command.ref)?.let { return it to true }
        loadSnapshot(command.ref)?.let { snapshot -> fromSnapshot(model, snapshot)?.let { return it to false } }
        return replay(model, CachedState(model.initialState(command.ref), 0), log.read(command.ref).toList()) to false
    }

    /**
     * [outcome], decided on [loaded] without appending, keeping [loaded] cached: decided without evolving, the state
     * is as loaded (decide purity is the user's contract, F4). Null when [loaded] came from the cache ([cached]) and
     * the log has moved past it ([behindLog]): the command is decided again on a reloaded state.
     */
    private suspend fun standing(
        ref: AggregateRef,
        loaded: CachedState,
        cached: Boolean,
        outcome: CommandOutcome,
    ): CommandOutcome? {
        if (cached && behindLog(ref, loaded.version)) {
            return null
        }
        states.put(ref, loaded)
        return outcome
    }

    /**
     * Whether the log holds a stream of [ref] after [version], the version of a cached state a command was rejected
     * on or changed nothing on. Without fencing (decision D45), another node may have committed to the aggregate while
     * its owner's cache held it: a command that appends meets that stream as a version conflict, but one that appends
     * nothing must read the next version slot, or it answers from a state the log has moved past.
     */
    private suspend fun behindLog(ref: AggregateRef, version: Int): Boolean =
        log.read(ref, version + 1, version + 1).firstOrNull() != null

    /**
     * The committed state of [ref] at [version], read-only (design 04 §1: the state a subscription hands over when
     * the commit's state view is gone, on a replay or after an ownership change): [snapshot] evolved with the log's
     * streams after it up to [version] when it fits, as on a cache miss, else the log replayed up to [version]. A
     * snapshot past [version], of another aggregate or not fitting is passed over. The state cache is not touched.
     *
     * @return the state at [version] with the metadata the kernel keeps beside it (scope, deleted mark, tags); null
     *   when the log does not hold [version].
     */
    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        version: Int,
        snapshot: SnapshotRecord? = null,
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        require(version >= 1) { "A committed state has a version from 1." }
        if (snapshot != null && snapshot.ref == ref && snapshot.version <= version) {
            fromSnapshot(model, snapshot, version)?.let { return it.takeIf { state -> state.version == version } }
        }
        val streams = log.read(ref, 1, version).toList()
        if (streams.lastOrNull()?.version != version) {
            return null
        }
        return replay(model, CachedState(model.initialState(ref), 0), streams)
    }

    /**
     * The committed state of [ref] after [streams], its streams from version 1 in version order as the log holds them,
     * replayed without a snapshot (decision D172: a snapshot rebuilt from the log, in place of one that does not fit).
     * The state cache is not touched.
     *
     * @return the state with the metadata the kernel keeps beside it; null when [streams] do not start at version 1
     *   or skip a version.
     */
    fun <S : Any> replayed(
        model: AggregateModel<S>,
        ref: AggregateRef,
        streams: List<EventStreamRecord>
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        if (streams.isEmpty() || streams.withIndex().any { (index, stream) -> stream.version != index + 1 }) {
            return null
        }
        return replay(model, CachedState(model.initialState(ref), 0), streams)
    }

    /**
     * The committed state of [ref] at [version], evolved from [base], its state at an earlier version that the caller
     * hands over to be evolved (it may be mutated: a copy, never a live instance), with the log's streams after it up
     * to [version] (design 04 §1: a subscription behind the snapshot evolves the state it read for the previous
     * commit, instead of replaying the log from version 1). The state cache is not touched.
     *
     * @return the state at [version] with the metadata the kernel keeps beside it; null when the log does not hold
     *   every version after [base]'s up to [version].
     */
    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        version: Int,
        base: CachedState,
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        require(base.version in 0..version) { "The base at version ${base.version} is past $version." }
        if (base.version == version) {
            return derived(model, base)
        }
        val streams = log.read(ref, base.version + 1, version).toList()
        if (streams.firstOrNull()?.version != base.version + 1 || streams.last().version != version) {
            return null
        }
        return replay(model, base, streams)
    }

    /**
     * The committed state of [ref] as of [at], read-only (decision D40, design 03 §4): the state after the last stream
     * committed at or before [at] by its commit time. Commit times are epoch milliseconds, so [at] counts to its
     * millisecond. The log is read in version order and the first stream committed after [at] ends it: commit times
     * are taken to rise with the versions, which only a clock stepping back between two owners of the aggregate
     * breaks, and only by that step.
     *
     * [snapshot] serves as for a version (see the other [stateAt]) when its last stream committed at or before [at]:
     * it is evolved with the tail up to [at], so the log is not replayed from version 1. A snapshot committed after
     * [at], of another aggregate or not fitting is passed over. Every event is read as the model's current schema,
     * upgraded from the revision it was written in (D82). The state cache is not touched.
     *
     * @return the state with the metadata the kernel keeps beside it (scope, deleted mark, tags); null when no stream
     *   of [ref] had committed by [at].
     */
    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        at: Instant,
        snapshot: SnapshotRecord? = null,
    ): CachedState? {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        val by = at.epochMillis()
        if (snapshot != null && snapshot.ref == ref && snapshot.lastEventTime <= by) {
            fromSnapshot(model, snapshot, committedBy = by)?.let { return it }
        }
        val streams = log.read(ref).takeWhile { it.commitTime <= by }.toList()
        if (streams.isEmpty()) {
            return null
        }
        return replay(model, CachedState(model.initialState(ref), 0), streams)
    }

    /**
     * The commits of [ref] from version [fromVersion] up to [toVersion], at most [limit] of them, in version order,
     * read-only (decision D40, design 03 §4): each stream with its events, read as the model's current schema (D82),
     * and the state and metadata after it. The state before [fromVersion] is the one [stateAt] gives for its version,
     * from [snapshot] and the tail when it fits, so the log is not replayed from version 1 then. The state cache is
     * not touched.
     *
     * @return the commits, empty when the log holds no version from [fromVersion] up to [toVersion].
     */
    @Suppress("LongParameterList")
    suspend fun <S : Any> trace(
        model: AggregateModel<S>,
        ref: AggregateRef,
        fromVersion: Int,
        toVersion: Int,
        limit: Int,
        snapshot: SnapshotRecord? = null,
    ): List<CommitTrace> {
        require(ref.type == model.type) { "$ref is not a ${model.type}." }
        require(fromVersion >= 1) { "A commit has a version from 1." }
        require(toVersion >= fromVersion) { "A trace ends at or after its first version." }
        require(limit >= 1) { "A trace holds at least one commit." }
        var current = if (fromVersion == 1) {
            CachedState(model.initialState(ref), 0)
        } else {
            stateAt(model, ref, fromVersion - 1, snapshot) ?: return emptyList()
        }
        val last = minOf(toVersion.toLong(), fromVersion.toLong() + limit - 1).toInt()
        val traces = ArrayList<CommitTrace>()
        log.read(ref, fromVersion, last).collect { stream ->
            val events = ArrayList<Any>(stream.events.size)
            current = evolve(model, current, stream, events)
            // The state may be mutable and evolve further: the trace keeps a tree of it as it is now.
            traces += CommitTrace(stream, events, codec.stateTree(current.state), current.deleted, current.tags)
        }
        return traces
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
     * at another state revision (decision D172) or for another state schema, not decodable, or newer than the log.
     * The tail is read from the snapshot's own version, so the same read proves the log holds that version; that
     * stream is already folded and is skipped. The
     * tail stops at [until], and before the first stream committed after [committedBy] (epoch milliseconds): a
     * snapshot whose own stream committed after it does not fit either.
     */
    private suspend fun <S : Any> fromSnapshot(
        model: AggregateModel<S>,
        snapshot: SnapshotRecord,
        until: Int = Int.MAX_VALUE,
        committedBy: Long = Long.MAX_VALUE,
    ): CachedState? {
        if (snapshot.stateRevision != model.stateRevision) {
            logger.debug { "The snapshot of ${snapshot.ref} is of state revision ${snapshot.stateRevision}; replaying its log." }
            return null
        }
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
        val streams = log.read(
            snapshot.ref,
            snapshot.version,
            until
        ).takeWhile { it.commitTime <= committedBy }.toList()
        if (streams.firstOrNull()?.version != snapshot.version) {
            logger.warn { "The snapshot of ${snapshot.ref} is at version ${snapshot.version}, beyond its log; replaying it." }
            return null
        }
        val restored =
            CachedState(state, snapshot.version, snapshot.scope, snapshot.deleted, snapshot.tags, snapshot.schedules)
        // Its stored tags are the effective ones it was written with: the keys the state derives are derived anew.
        return replay(model, derived(model, restored), streams.subList(1, streams.size))
    }

    /**
     * Evolves [initial] with the events of [streams]: the state, and the deleted mark and tags by the lifecycle
     * events; the version and the scope are the last stream's, or [initial]'s when there is none.
     */
    private fun <S : Any> replay(
        model: AggregateModel<S>,
        initial: CachedState,
        streams: List<EventStreamRecord>,
    ): CachedState = streams.fold(initial) { current, stream -> evolve(model, current, stream) }

    /**
     * Evolves [current] with the events of [stream], adding each event, read as the model's current schema, to
     * [events] when given: the state, and the deleted mark and tags by the lifecycle events; the version and the
     * scope become the stream's.
     */
    private fun <S : Any> evolve(
        model: AggregateModel<S>,
        current: CachedState,
        stream: EventStreamRecord,
        events: MutableList<Any>? = null,
    ): CachedState {
        @Suppress("UNCHECKED_CAST") // The state is built by this model, or read as its state type.
        var state = current.state as S
        var metadata = current.metadata
        stream.events.forEach { record ->
            // A retired event (D219) is skipped: the stream still counts as a version, the state stays as it is.
            val event = codec.decodeUnlessRetired(model, record) ?: return@forEach
            events?.add(event)
            state = model.evolve(state, event)
            metadata = KernelLifecycle.evolve(metadata, event)
        }
        return current.with(
            state,
            stream.version,
            metadata.copy(scope = stream.scope, tags = model.tagsOf(state, metadata.tags)),
        )
    }

    /**
     * [cached] with the tags its state derives (decision D220) overriding its own: a state the kernel did not evolve
     * itself, read from a snapshot or handed over, whose stored tags may predate the model's tags function.
     */
    private fun <S : Any> derived(model: AggregateModel<S>, cached: CachedState): CachedState {
        if (cached.version == 0) {
            return cached
        }
        @Suppress("UNCHECKED_CAST") // The state is built by this model, or read as its state type.
        val tags = model.tagsOf(cached.state as S, cached.tags)
        return if (tags == cached.tags) {
            cached
        } else {
            cached.with(cached.state, cached.version, cached.metadata.copy(tags = tags))
        }
    }

    /**
     * Refuses [command] before deciding when the aggregate as [loaded] cannot take it, checked in this order: it does
     * not exist and the command does not create it; it exists in another scope than the command's (D94); it is
     * deleted and the command does not restore it; it exists and the command must create it; it is not at the
     * expected version.
     *
     * The scope comes right after existence, so a caller outside the aggregate's scope learns only that it exists,
     * which a colliding creation would tell it anyway: not whether it is deleted, nor its version.
     */
    private fun admissible(decider: DecideFunction<*>, command: Command<*>, loaded: CachedState): CommandOutcome? {
        val version = loaded.version
        return when {
            version == 0 && decider.creation == Creation.NONE ->
                rejected(KernelRejections.NOT_FOUND, "${command.ref} does not exist.")

            version > 0 && !command.isDerived && !admits(loaded.scope, command.scope) -> forbidden(command)

            loaded.deleted && command.body != RestoreAggregate ->
                rejected(KernelRejections.DELETED, "${command.ref} is deleted.")

            version > 0 && decider.creation == Creation.REQUIRED ->
                rejected(KernelRejections.EXISTS, "${command.ref} already exists.")

            command.expectedVersion != null && command.expectedVersion != version ->
                rejected(
                    KernelRejections.VERSION_MISMATCH,
                    "${command.ref} is at version $version, not ${command.expectedVersion}.",
                )

            else -> null
        }
    }

    /** The decision of [decider] on [state], its events followed by those of [model]'s after hooks (D39). */
    private suspend fun <S : Any> decideAll(
        model: AggregateModel<S>,
        decider: DecideFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): Decision {
        val decision = decide(decider, scope, state, body)
        val decided = when (decision) {
            is Decision.Events -> decision.events
            Decision.NoOp -> emptyList()
            is Decision.Rejected -> return decision
        }
        val afters = model.afterFunctions(body.javaClass)
        if (afters.isEmpty()) {
            return decision
        }
        val domain = decided + afters.flatMap { after(it, scope, state, body) }
        return if (domain.isEmpty()) Decision.NoOp else Decision.Events(domain)
    }

    private suspend fun <S : Any> decide(
        decider: DecideFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): Decision = when (decider) {
        is DecideFunction.Sync -> decider.decide(scope, state, body)
        is DecideFunction.Suspending -> if (decider.blocking) {
            withContext(blocking) { decider.decide(scope, state, body) }
        } else {
            decider.decide(scope, state, body)
        }
    }

    private suspend fun <S : Any> after(
        after: AfterFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): List<Any> = when (after) {
        is AfterFunction.Sync -> after.after(scope, state, body)
        is AfterFunction.Suspending -> if (after.blocking) {
            withContext(blocking) { after.after(scope, state, body) }
        } else {
            after.after(scope, state, body)
        }
    }

    /**
     * Answers [command], whose request id [existing] already committed: a retry gets that stream back, before the
     * aggregate is loaded and without deciding again (D46).
     *
     * A retry comes from the scope that sent the command, so the answer is given only within that scope (D94): a
     * caller outside it, reusing or guessing another caller's request id, learns neither the stream nor which command
     * committed it, so this check comes before the command types are compared. That scope is the aggregate's before
     * the stream, but the stream records only its scope after; the two differ only in a part the stream's own events
     * transferred, and that part is not checked: retrying a transfer is still answered though the caller no longer
     * holds the aggregate. A derived command is answered whatever its scope (decision D199): its retry is the server
     * handling the same commit again, not a caller.
     */
    private fun duplicate(existing: EventStreamRecord, command: Command<*>, commandType: CommandType): CommandOutcome {
        val names = existing.events.mapTo(HashSet()) { it.name }
        val before = Scope(
            if (Lifecycle.OWNER_TRANSFERRED in names) command.scope.ownerId else existing.scope.ownerId,
            if (Lifecycle.SPACE_TRANSFERRED in names) command.scope.spaceId else existing.scope.spaceId,
        )
        return if (command.isDerived || admits(before, command.scope)) {
            settled(existing, commandType)
        } else {
            forbidden(command)
        }
    }

    /** Whether an aggregate in scope [aggregate] takes a command in scope [caller]: each part the caller has matches. */
    private fun admits(aggregate: Scope, caller: Scope): Boolean =
        (caller.ownerId == null || caller.ownerId == aggregate.ownerId) &&
            (caller.spaceId == null || caller.spaceId == aggregate.spaceId)

    private fun forbidden(command: Command<*>) =
        rejected(KernelRejections.FORBIDDEN, "${command.ref} is outside the command's scope.")

    /** A stream recorded under the command's name, or a former one (`@Alias`), is the same command's (D55). */
    private fun settled(existing: EventStreamRecord, commandType: CommandType): CommandOutcome =
        if (commandType.isNamed(existing.commandType)) {
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

        class Append(val stream: EventStreamRecord, val evolved: CachedState) : Prepared
    }

    /** One attempt's registrations. Allocates its lists only when a decide function registers something. */
    @Suppress("LongParameterList")
    private class AttemptScope(
        override val command: Command<*>,
        override val ids: Ids,
        override val loaded: CachedState,
        private val reservations: ReservationStore?,
        private val reservationTtl: Duration,
        private val wait: WaitContext?,
        /** The type name and JSON of a command scheduled for a target. */
        private val encode: (AggregateRef, Any) -> Pair<String, String>,
    ) : DecideScope, KernelLifecycle.MetadataScope {
        private var compensations: MutableList<suspend () -> Unit>? = null
        private var afterAppendActions: MutableList<suspend () -> Unit>? = null
        private var results: MutableMap<String, Any>? = null
        private var reserved: MutableList<ReservationKey>? = null
        private var released: MutableList<ReservationKey>? = null
        private var hold: String? = null
        private var scheduled: LinkedHashMap<String, Any>? = null

        override fun schedule(key: String, rule: ScheduleRule, command: Any, target: AggregateRef) {
            require(key.isNotBlank()) { "A schedule needs a key." }
            val (type, body) = encode(target, command)
            changes().apply {
                remove(key)
                put(key, ScheduleSet(key, rule, target, type, body))
            }
        }

        override fun cancelSchedule(key: String) {
            require(key.isNotBlank()) { "A schedule needs a key." }
            changes().apply {
                // A key set earlier in this decision and not kept before: the set is undone, nothing to cancel.
                val undone = remove(key) is ScheduleSet && key !in loaded.schedules
                if (!undone) {
                    put(key, ScheduleCancelled(key))
                }
            }
        }

        private fun changes() = scheduled ?: LinkedHashMap<String, Any>().also { scheduled = it }

        /** The schedule events of this decision, in the order their keys were last changed. */
        fun scheduleEvents(): List<Any> = scheduled?.values?.toList().orEmpty()

        override fun putResult(key: String, value: Any) {
            (results ?: LinkedHashMap<String, Any>().also { results = it })[key] = value
        }

        fun results(): Map<String, Any> = results ?: emptyMap()

        /**
         * What the append carries for delivery: the wait context when the caller waits beyond COMMITTED (D25), the
         * keys this attempt reserved pending (D50) and released (A2-4), and the append's [trace] (D174); null when
         * there is none of them.
         */
        fun transport(
            trace: Map<String, String>
        ): Transport? = if (wait == null && reserved == null && released == null) {
            trace.takeIf { it.isNotEmpty() }?.let { Transport(trace = it) }
        } else {
            Transport(
                wait = wait,
                reservations = reserved?.toList().orEmpty(),
                trace = trace,
                releases = released?.toList().orEmpty(),
            )
        }

        override suspend fun reserve(namespace: String, key: String): Boolean =
            reserve(namespace, key, ReservationTenancy.TENANT)

        override suspend fun reserve(namespace: String, key: String, tenancy: ReservationTenancy): Boolean {
            val store = checkNotNull(reservations) { "Reserving a key needs a kernel with a ReservationStore." }
            val reservation = ReservationKey(namespace, key, tenancy == ReservationTenancy.SYSTEM)
            if (reserved?.contains(reservation) == true) {
                return true
            }
            // The aggregate owns the key, field by field (finding A1-10), within its context and tenant (A1-3). The
            // attempt holds a pending reservation by a hold of its own, so its compensation gives up its hold only and
            // never another attempt's of the same aggregate, nor a confirmed key (A1-2).
            val owner = command.ref
            val scope = ReservationScope.of(owner, reservation)
            val hold = hold ?: UUID.randomUUID().toString().also { hold = it }
            val held = store.reserve(scope, key, owner, hold, reservationTtl) ?: return false
            if (!held.confirmed) {
                (reserved ?: ArrayList<ReservationKey>().also { reserved = it }) += reservation
                onAppendFailure { store.abandon(scope, key, owner, hold) }
                afterAppend { confirmed(store.confirm(scope, key, owner), scope, key) }
            }
            return true
        }

        override fun release(namespace: String, key: String) = release(namespace, key, ReservationTenancy.TENANT)

        override fun release(namespace: String, key: String, tenancy: ReservationTenancy) {
            val store = checkNotNull(reservations) { "Releasing a key needs a kernel with a ReservationStore." }
            val reservation = ReservationKey(namespace, key, tenancy == ReservationTenancy.SYSTEM)
            if (released?.contains(reservation) == true) {
                return
            }
            (released ?: ArrayList<ReservationKey>().also { released = it }) += reservation
            val owner = command.ref
            val scope = ReservationScope.of(owner, reservation)
            afterAppend { store.release(scope, key, owner) }
        }

        /** Logs a key the committed fact lost to another owner: confirming cannot get it back. */
        private fun confirmed(outcome: ConfirmOutcome, scope: ReservationScope, key: String) {
            if (outcome is ConfirmOutcome.Taken) {
                logger.error {
                    "${command.ref} committed with $key in $scope, which ${outcome.by.owner} holds: the key is lost."
                }
            }
        }

        override fun onAppendFailure(compensation: suspend () -> Unit) {
            (compensations ?: ArrayList<suspend () -> Unit>().also { compensations = it }) += compensation
        }

        override fun afterAppend(action: suspend () -> Unit) {
            (afterAppendActions ?: ArrayList<suspend () -> Unit>().also { afterAppendActions = it }) += action
        }

        /**
         * Runs the compensations, latest first; their failures join [cause] when there is one, else they are logged.
         * They run whether or not the attempt is cancelled (finding A1-5): an attempt cancelled while deciding, say a
         * saga's lane stopped by a handover, must still undo what its decide functions did outside the log.
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun compensate(cause: Throwable? = null) {
            val registered = compensations ?: return
            withContext(NonCancellable) {
                registered.asReversed().forEach { compensation ->
                    try {
                        compensation()
                    } catch (failure: Throwable) {
                        cause?.addSuppressed(failure) ?: logger.warn(failure) { "A compensation failed." }
                    }
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

        /** [this] in epoch milliseconds, the unit of commit times; an instant beyond them is the first or the last. */
        fun Instant.epochMillis(): Long = try {
            toEpochMilli()
        } catch (_: ArithmeticException) {
            if (isBefore(Instant.EPOCH)) Long.MIN_VALUE else Long.MAX_VALUE
        }
        val DEFAULT_RESERVATION_TTL = 30.seconds
        val DEFAULT_MAX_EARLY_WAIT = 5.seconds
        val logger = KotlinLogging.logger {}
    }
}

/**
 * Names the commands a decide function schedules for an aggregate of another type than its own (decision D166): the
 * full type name (`context.aggregate.Command`) of the [command] class sent to an aggregate of [target], or null when
 * none is known. The runtime names them from the context's models and the Manifests of the service and its peers.
 */
@InternalWowApi
fun interface CommandTypes {
    fun nameOf(target: AggregateType, command: Class<*>): String?

    @InternalWowApi
    companion object {
        /** Names none: a decide function schedules commands of its own aggregate type only. */
        @JvmField
        val NONE: CommandTypes = CommandTypes { _, _ -> null }

        /**
         * Names a command by its class's simple name in its target's type (`context.aggregate.Command`), the name a
         * model gives a command unless it says otherwise: for a saga outside a node, in a domain module's test.
         */
        @JvmField
        val SIMPLE_NAMES: CommandTypes = CommandTypes { target, command -> "$target.${command.simpleName}" }
    }
}

/**
 * Whether this command was derived by the server's own code rather than sent by a caller (decision D199): a saga's,
 * an `@OnRejected` compensation, a timer's, or one another context sent through its command topic. Such a command
 * belongs to a chain, so it carries a [Command.causation]; admission gives a caller's none (it is its own root).
 */
@InternalWowApi
val Command<*>.isDerived: Boolean get() = causation != null

/** How an execution ended, as its observation names it (decision D174). */
internal val CommandOutcome.observedName: String
    get() = when (this) {
        is CommandOutcome.Committed -> "committed"
        is CommandOutcome.AlreadyCommitted -> "duplicate"
        is CommandOutcome.Unchanged -> "unchanged"
        is CommandOutcome.Rejected -> "rejected"
        is CommandOutcome.Contended -> "contended"
        is CommandOutcome.Unknown -> "unknown"
    }

/** How an append ended, as its observation names it (decision D174). */
internal val AppendOutcome.observedName: String
    get() = when (this) {
        is AppendOutcome.Committed -> "committed"
        is AppendOutcome.AlreadyCommitted -> "duplicate"
        is AppendOutcome.Conflict -> "conflict"
        is AppendOutcome.Unknown -> "unknown"
        is AppendOutcome.Rejected -> "rejected"
    }
