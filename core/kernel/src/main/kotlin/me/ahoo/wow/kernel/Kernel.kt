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
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.decide.RejectedException
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.wait.WaitContext
import tools.jackson.core.JacksonException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The write kernel: turns one admitted command into at most one committed event stream
 * (documentation/designs/02-write-path.md §1).
 *
 * 1. **Deduplicate before deciding**: a request id already committed in the tenant is never decided again; a
 *    command of another type reusing it is rejected. Commands are told apart by their model name only, not by a hash
 *    of their body: a same-type command reusing a request id is taken for a retry, by the caller's contract.
 *    A duplicate is answered only within the scope that sent it (see [duplicate]). Each loaded aggregate carries the
 *    [KernelOptions.recentRequests] it committed last, derived from the log: a request they hold is answered from
 *    its stream, and on a miss most commands skip looking the request id up; see [RequestCheck] for when they still
 *    do. The append's request key stays the backstop of "committed at most once". [KernelOptions.recentRequests] 0 looks every
 *    request id up before deciding.
 * 2. **Load** the committed state, **decide** (the command's function, then its after hooks), **evolve** a
 *    fresh state with the events, **append** once.
 * 3. A version conflict decides again on the newer state, up to [KernelOptions.maxAttempts]; an unknown append outcome is settled
 *    by reading the version slot.
 * 4. A rejection or an unchanged outcome decided on a cached state stands only once the log holds nothing after the
 *    state's version: one read of the next version slot, else the state is reloaded and the command decided again
 *    (not counted as an attempt). Another node of the service may have appended, and only an
 *    append meets the version-unique key.
 *
 * An attempt whose events are surely not appended (rejected, conflicting, failing) runs the compensations its
 * decide functions registered ([DecideScope.onAppendFailure]); a committed one runs their after-append actions. Once
 * an append commits, the rest of the attempt does not observe cancellation: a caller giving up must not tear what is
 * already a fact.
 *
 * [KernelOptions.verifyDecidePurity] makes the kernel fail a command whose decide function or after hook changed the state: with
 * mutable state nothing else stops that, and a change that bypasses events silently drifts from the log. The
 * test DSL turns it on; it costs two serializations of the state per command. A fresh state (version 0) with a
 * `lateinit` property still unassigned cannot be written, and is not checked: the creation event's sourcing assigns
 * it, and no state is written before that.
 *
 * [states] keeps states between commands; see [StateCache] for when a state is kept or dropped, and [StateSourcing]
 * for where a state comes from on a miss.
 *
 * [reservations] backs [DecideScope.reserve] and [DecideScope.release]; a pending reservation lives for
 * [KernelOptions.reservationTtl] unless the append confirms it first. Each attempt reserves by a hold of its own, so an attempt
 * surely not appended abandons only its own hold. The keys an attempt reserved, held already or not, and those it
 * released, go into the appended entry's transport, so the `reservation` subscription confirms and releases
 * them even when the after-append actions never run. A rejection that commits events gives its keys up as one
 * that writes nothing does: its entry carries none of them.
 *
 * Every stream carries the aggregate's scope after it: the creating command's scope, carried forward by every
 * later command and changed only by the lifecycle commands transferring the owner or the space (see [metadataAfter]).
 * The kernel keeps the deleted mark beside the state too, evolved by the lifecycle events: a deleted
 * aggregate refuses every command but [RestoreAggregate] ([FrameworkCodes.AGGREGATE_DELETED]).
 *
 * The aggregate's scope also guards it, by the default rule ([me.ahoo.wow.api.access.ScopeAccess]) on the command's
 * scope ([admissible]): an existing aggregate whose owner or space is another than the one the command's scope names
 * is out of the caller's reach, and is answered as one that does not exist ([FrameworkCodes.AGGREGATE_NOT_FOUND]), or
 * [FrameworkCodes.AGGREGATE_FORBIDDEN] for a command that may create it. A part absent from the command's scope is not
 * checked: a tenant-level caller's, or an administrator's acting across scopes, carries no owner. Creating an
 * aggregate gives it the creating command's scope, and is checked again on the aggregate it creates ([creatable]).
 * An aggregate that belongs to its tenant ([AggregateModel.ownership]) neither records nor checks an owner: only the
 * space of a command counts. An aggregate whose id is its owner's ([AggregateModel.idSource]) records its id as its
 * owner, whoever creates it, so a caller of another owner neither creates nor changes it.
 * A transfer of the owner or the space is guarded like any other command before deciding: its scope is the caller's,
 * so the current owner (or a member of the current space) transfers, and the new owner or space is in its body. It
 * is not checked after: it gives the aggregate away on purpose.
 *
 * A derived command ([isDerived]: a saga's, an `@OnRejected` compensation, a timer's, one another context sent
 * through its command topic) is not guarded: the server's own code made it, there is no caller to
 * check, and what it may do to whom is the domain's to decide, such as A's transfer crediting B's account. The root
 * command was authorized when it was admitted. The scope it inherits from the root only gives an aggregate it creates
 * its ownership and is recorded for the audit; a retry of one is answered whatever the aggregate's scope.
 *
 * A decide function's schedules ([DecideScope.schedule], [DecideScope.cancelSchedule]) are the
 * system events `wow.ScheduleSet` and `wow.ScheduleCancelled` after its events in the stream it commits, the last
 * change of each key: they commit with its events, or not at all, and a decision that only schedules commits them
 * alone (on an aggregate that exists). The command a schedule sends is stored by its type name and JSON:
 * [commandTypes] names a command of an aggregate type other than the deciding one. Every aggregate also decides the
 * system commands `wow.schedule.Set` and `wow.schedule.Cancel` a saga derives ([KernelLifecycle]); the kernel keeps
 * the rules of the schedules an aggregate holds beside its state ([CachedState.schedules]).
 *
 * A command a schedule sent ([Command.schedule]) is not decided before the time its slot was due by this kernel's
 * [clock]: a node whose clock is behind the firing node's waits until then, so a schedule never fires
 * early. It waits at most [KernelOptions.maxEarlyWait], holding the aggregate's queue meanwhile: a command due later than that fails
 * at once with [ScheduleNotDueException], and its firing runner sends it again later.
 *
 * A decide function takes its ids from [ids] ([DecideScope.ids]): the node's generators, fixed ids in
 * the test DSL.
 *
 * A decide function or after hook marked `@Blocking` ([DecideFunction.Suspending.blocking]) runs on
 * [blocking], by default the node's virtual threads ([BlockingDispatcher]); every other one runs inline in the
 * caller's coroutine.
 *
 * [observer] sees each execution, decision and append; what an append carries for delivery holds the
 * append's trace ([Transport.trace]), so the subscriptions that handle it continue the command's trace.
 */
@InternalWowApi
@Suppress("LargeClass") // The write path: one command, or a batch of an aggregate's, on one state.
class Kernel(
    private val log: EventLog,
    private val codec: EventCodec = EventCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val reservations: ReservationStore? = null,
    private val states: StateCache = StateCache.None,
    snapshots: SnapshotStore? = null,
    private val commandTypes: CommandTypes = CommandTypes.NONE,
    private val ids: Ids = IdGenerators.DEFAULT,
    private val observer: Observer = Observer.NONE,
    private val blocking: CoroutineContext = BlockingDispatcher.dispatcher,
    /** The kernel's tuning: attempts, purity check, reservations, early waits, recent requests, replays. */
    private val options: KernelOptions = KernelOptions(),
    /**
     * The one-round-trip read of each aggregate type's snapshot with its tail, for the types whose log and primary
     * snapshots are on one store that reads them together; none, the reads are done apart.
     */
    snapshotTailReaders: (AggregateType) -> SnapshotTailReader? = { null },
    /**
     * The access policy of each aggregate type, which decides who changes it before and after deciding; none, every
     * aggregate is changed by the default rule ([me.ahoo.wow.api.access.ScopeAccess]).
     */
    private val policies: WritePolicies = WritePolicies.NONE,
) {
    /** Where a state comes from on a cache miss, and how streams fold into it. */
    private val source = StateSourcing(log, codec, observer, snapshots, snapshotTailReaders)

    /** The reads of a committed state at a version or a time, and of its history. */
    private val history = StateHistory(source, log, codec)

    private val maxAttempts: Int get() = options.maxAttempts
    private val recentRequests: Int get() = options.recentRequests

    /**
     * Executes [admitted] on [model]'s aggregate, its scope narrowed to what the model's ownership keeps
     * ([me.ahoo.wow.api.aggregate.Ownership.scopeOf]).
     *
     * [wait] is the wait context the runtime built for the command's wait plan, if any: when it still has hops to
     * reach after COMMITTED, it is appended with the stream as transport, so the subscription its next
     * hop names signals the waiting node. The kernel only carries it; registering the wait and the signals are the
     * runtime's.
     *
     * [appending], when given, is called with each stream the kernel is about to append and the state after it, before
     * the append starts: the runtime keeps that state as the view a local subscription reads if
     * the log hands it the commit before [execute] returns. It is called once per attempt, so a stream
     * it was given may never commit; it must not throw.
     */
    suspend fun <S : Any> execute(
        model: AggregateModel<S>,
        admitted: Command<*>,
        wait: WaitContext? = null,
        appending: ((EventStreamRecord, CachedState) -> Unit)? = null,
    ): CommandOutcome {
        require(admitted.ref.type == model.type) { "${admitted.ref} is not a ${model.type}." }
        require(admitted.ref.isAssigned) { "${admitted.ref} names no aggregate: admission assigns its id." }
        val type = admitted.ref.type
        val commandType = model.findCommandType(admitted.body.javaClass)?.name
            ?: "${type.context}.${type.name}.${admitted.body.javaClass.simpleName}"
        val executing = observer.executing(admitted, commandType)
        var committed: CommandOutcome.Committed? = null
        return try {
            executing.observe {
                executeAdmitted(model, admitted, wait, appending).also {
                    committed = it as? CommandOutcome.Committed
                    executing.outcome(it.observedName)
                }
            }
        } catch (cancelled: CancellationException) {
            // An observation's context returns through a cancellable switch: a commit that switch
            // dropped for a caller cancelled meanwhile is still answered, so its shared state is recorded.
            committed ?: throw cancelled
        }
    }

    /**
     * Executes [queued], commands of one aggregate in arrival order, deciding each on the state the ones before it left
     * and appending their streams with one [EventLog.appendAll]: the aggregate's commands are
     * committed together instead of one append each. Each command's outcome is what [execute] would have given it.
     *
     * The first command always runs, alone through [execute] when it cannot start a batch. A batch takes the commands
     * after it while each one is pure (its decide function and after hooks do no I/O, so deciding it again costs
     * nothing), is not sent for a schedule, is not among the aggregate's recent requests or the batch's own, and
     * appends a stream; it ends before the first that is not, which is [BatchOutcome.Again]: the lane runs it next,
     * alone if need be. The streams commit as a prefix ([EventLog.appendAll]); the first that does not commit is
     * settled as [execute] settles an append, and the commands after it, compensated, are [BatchOutcome.Again]: their
     * streams were decided on a state that did not commit. The aggregate's state is then dropped from the cache, as
     * a later stream evolved it.
     */
    suspend fun <S : Any> executeBatch(
        model: AggregateModel<S>,
        queued: List<QueuedCommand>,
        appending: ((EventStreamRecord, CachedState) -> Unit)? = null,
    ): List<BatchOutcome> {
        require(queued.isNotEmpty()) { "A batch holds at least one command." }
        val ref = queued.first().command.ref
        require(queued.all { it.command.ref == ref }) { "A batch holds the commands of one aggregate." }
        // Without recent requests every request id is looked up first: each command runs alone.
        val members = if (recentRequests > 0) membersOf(model, queued, appending) else BatchMembers()
        if (members.streams.isEmpty()) {
            return listOf(alone(model, queued.first(), appending)) + List(queued.size - 1) { BatchOutcome.Again }
        }
        val outcomes = commitBatch(members) + List(queued.size - members.streams.size) { BatchOutcome.Again }
        // The first stream did not commit and is decided again: alone, within execute's attempts, so the lane always
        // makes progress.
        // A command that failed deciding may have evolved the state part way: it is dropped, and reloaded.
        if (members.tainted) states.take(ref)
        return if (outcomes.first() is BatchOutcome.Again) {
            listOf(alone(model, queued.first(), appending)) + outcomes.drop(1)
        } else {
            outcomes
        }
    }

    /** [entry] executed alone, through [execute]: its outcome, or its failure. */
    private suspend fun <S : Any> alone(
        model: AggregateModel<S>,
        entry: QueuedCommand,
        appending: ((EventStreamRecord, CachedState) -> Unit)?,
    ): BatchOutcome = try {
        BatchOutcome.Done(execute(model, entry.command, entry.wait, appending))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught") // A command's failure is its own, as execute throws it.
        failure: Exception,
    ) {
        BatchOutcome.Failed(failure)
    }

    /**
     * The batch [queued] starts: the stream of each command it takes, decided in order on the state the ones before
     * it left. Empty when the first command cannot start one: its state, if loaded, is cached again unchanged.
     */
    private suspend fun <S : Any> membersOf(
        model: AggregateModel<S>,
        queued: List<QueuedCommand>,
        appending: ((EventStreamRecord, CachedState) -> Unit)?,
    ): BatchMembers {
        val lease = currentCoroutineContext()[ExecutionLease]
        val members = BatchMembers()
        var state: CachedState? = null
        var caching = false
        for (entry in queued) {
            val command = entry.command.narrowedTo(model)
            val decider = model.decider(command.body.javaClass) ?: break
            val commandType = model.commandType(command.body.javaClass)
            val reach = CommandReach(model, policies.of(model.type), command)
            val requests = RequestCheck(log, states, recentRequests, model, decider, command, lease) {
                duplicate(it, command, commandType, reach, codec)
            }
            // A command with I/O to decide, or sent for a schedule, runs alone: it ends the batch, or is the first.
            if (!requests.pure || command.schedule != null) break
            val current = state ?: source.load(model, requests).first.also {
                state = it
                caching = requests.caching
            }
            val member = decided(model, decider, entry, command, commandType, requests, current, members, reach) ?: break
            members.streams += member
            // Before the next command evolves the same (mutable) state further.
            appending?.invoke(member.stream, member.evolved)
            state = member.evolved
        }
        if (members.streams.isEmpty() && caching && !members.tainted) {
            // Nothing evolved it: the state goes back as it was taken (C4: only under the lease), and the first command
            // runs alone. A first command that failed deciding may have evolved it part way: it is dropped.
            putBack(queued.first().command.ref, state)
        }
        return members
    }

    /** Caches [state] of [ref] again, unless it is the initial state of an aggregate not yet created. */
    private fun putBack(ref: AggregateRef, state: CachedState?) {
        state?.takeIf { it.version > 0 }?.let { states.put(ref, it) }
    }

    /**
     * [command] decided on [current] as a member of [members]' batch; null when it cannot join it: a request the
     * aggregate or the batch holds, one refused before deciding, or one that fails, appends nothing or is refused
     * deciding (each decided again alone, at no cost: it is pure).
     */
    @Suppress("LongParameterList")
    private suspend fun <S : Any> decided(
        model: AggregateModel<S>,
        decider: DecideFunction<S>,
        entry: QueuedCommand,
        command: Command<*>,
        commandType: CommandType,
        requests: RequestCheck,
        current: CachedState,
        members: BatchMembers,
        reach: CommandReach,
    ): BatchMember? {
        val repeated = current.requests?.versionOf(command.requestId) != null ||
            members.streams.any { it.stream.requestId == command.requestId }
        if (repeated || admissible(decider, command, current, reach) != null) return null
        val encode = { target: AggregateRef, body: Any -> scheduledCommand(model, target, body) }
        val wait = entry.wait.beyondCommitted()
        val attempt =
            AttemptScope(command, ids, clock(), current, reservations, options.reservationTtl, wait, observer, encode)
        val prepared = try {
            prepare(model, decider, attempt, current, command, commandType.name, reach)
        } catch (cancelled: CancellationException) {
            attempt.compensate(cancelled)
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") // Decided again alone, where it fails as execute fails it.
            failure: Exception,
        ) {
            attempt.compensate(failure)
            members.tainted = true
            return null
        }
        if (prepared !is Prepared.Append) {
            attempt.compensate()
            // Refused once its events evolved the state, which may have changed in place: the state is dropped.
            if ((prepared as Prepared.Done).evolved) members.tainted = true
            return null
        }
        return BatchMember(command, commandType, requests, attempt, prepared.stream, prepared.evolved)
    }

    /** Appends the batch's streams and settles each: the outcomes of [members], in order. */
    private suspend fun commitBatch(members: BatchMembers): List<BatchOutcome> = withContext(NonCancellable) {
        val batch = members.streams
        val observations = batch.map { observer.appending(it.stream) }
        val appended = try {
            log.appendAll(
                batch.mapIndexed { at, member ->
                    val transport = member.attempt.transport(observations[at].trace, member.stream.rejection != null)
                    member.stream to transport?.let(TransportCodec::encode)
                }
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") // Compensates whatever failed; each command fails with it.
            failure: Exception,
        ) {
            batch.forEach { it.attempt.compensate(failure) }
            states.take(batch.first().stream.ref)
            return@withContext batch.map { BatchOutcome.Failed(failure) }
        }
        var stopped = false
        batch.mapIndexed { at, member ->
            observations[at].outcome(appended[at].observedName)
            if (stopped) {
                member.attempt.compensate()
                return@mapIndexed BatchOutcome.Again
            }
            val settled = settled(member.stream, appended[at])
            if (settled !is AppendOutcome.Committed) {
                stopped = true
            }
            try {
                settleAppend(settled, member.stream, member.evolved, member.attempt, member.commandType, member.requests)
                    ?.let { outcome ->
                        observer.executing(member.command, member.commandType.name).outcome(outcome.observedName)
                        BatchOutcome.Done(outcome)
                    }
                    ?: BatchOutcome.Again
            } catch (
                @Suppress("TooGenericExceptionCaught") // A refused stream fails its own command, as execute throws it.
                failure: Exception,
            ) {
                BatchOutcome.Failed(failure)
            }
        }.also {
            // A stream after the last committed one evolved the cached state: it is dropped, and reloaded.
            if (stopped) states.take(batch.first().stream.ref)
        }
    }

    /** [outcome] of [stream]'s append, settled as [append] settles it. */
    private suspend fun settled(stream: EventStreamRecord, outcome: AppendOutcome): AppendOutcome = when (outcome) {
        is AppendOutcome.Unknown -> settleUnknown(stream, outcome)
        is AppendOutcome.AlreadyCommitted -> duplicateOf(stream, outcome)
        is AppendOutcome.Conflict -> settleConflict(stream, outcome)
        else -> outcome
    }

    /** The streams a batch takes, in order. */
    private class BatchMembers {
        val streams = ArrayList<BatchMember>()

        /** Whether a command failed deciding after the state was taken: it may have evolved it part way. */
        var tainted = false
    }

    /** One command of a batch, decided: its stream and the state after it. */
    private class BatchMember(
        val command: Command<*>,
        val commandType: CommandType,
        val requests: RequestCheck,
        val attempt: AttemptScope,
        val stream: EventStreamRecord,
        val evolved: CachedState,
    )

    private suspend fun <S : Any> executeAdmitted(
        model: AggregateModel<S>,
        admitted: Command<*>,
        wait: WaitContext?,
        appending: ((EventStreamRecord, CachedState) -> Unit)?,
    ): CommandOutcome {
        val command = admitted.narrowedTo(model)
        val decider = model.decider(command.body.javaClass)
            ?: return rejected(FrameworkCodes.COMMAND_UNHANDLED, "${model.type} does not handle ${command.body.javaClass.name}.")
        val commandType = model.commandType(command.body.javaClass)
        val lease = currentCoroutineContext()[ExecutionLease]
        val reach = CommandReach(model, policies.of(model.type), command)
        val requests = RequestCheck(log, states, recentRequests, model, decider, command, lease) {
            duplicate(it, command, commandType, reach, codec)
        }
        requests.beforeWaiting()?.let { return it }
        awaitDue(command)
        val waitBeyondCommitted = wait.beyondCommitted()
        val encode = { target: AggregateRef, body: Any -> scheduledCommand(model, target, body) }
        var attempts = 0
        while (attempts < maxAttempts) {
            val (loaded, cached) = source.load(model, requests)
            // Checked after every load: a reload may hold the request another node committed meanwhile.
            requests.beforeDeciding(loaded)?.let { return it }
            admissible(decider, command, loaded, reach)?.let {
                return requests.standing(loaded, cached, it) ?: continue
            }
            val attempt = AttemptScope(
                command, ids, clock(), loaded, reservations, options.reservationTtl, waitBeyondCommitted, observer, encode,
            )
            val prepared = try {
                prepare(model, decider, attempt, loaded, command, commandType.name, reach)
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
                    return unappended(prepared, requests, loaded, cached) ?: continue
                }

                is Prepared.Append -> prepared.stream to prepared.evolved
            }
            appending?.invoke(stream, evolved)
            val appended = withContext(NonCancellable) { appendCompensating(stream, attempt) }
            settleAppend(appended, stream, evolved, attempt, commandType, requests)?.let { return it }
            attempts++
        }
        return CommandOutcome.Contended(maxAttempts)
    }

    /**
     * The answer to [prepared], a decision on [loaded] that appends nothing, as [requests] gives it
     * ([RequestCheck.standing]), [loaded] dropped rather than cached again when the refused decision evolved it
     * ([Prepared.Done.evolved]); null to decide again on a reloaded state.
     */
    private suspend fun unappended(
        prepared: Prepared.Done,
        requests: RequestCheck,
        loaded: CachedState,
        cached: Boolean,
    ): CommandOutcome? = if (prepared.evolved) {
        requests.dropping(loaded, cached, prepared.outcome)
    } else {
        requests.standing(loaded, cached, prepared.outcome)
    }

    /**
     * Waits until the slot [command] was sent for is due on this kernel's clock, when it was sent for a schedule:
     * a schedule never fires before its time here, whatever the firing node's clock said. One due
     * later than [maxEarlyWait] from now is not waited for, holding the aggregate's queue: it throws
     * [ScheduleNotDueException], and its firing runner sends it again later.
     */
    private suspend fun awaitDue(command: Command<*>) {
        val due = (command.schedule ?: return).due
        val now = clock()
        val early = due - now
        if (early > options.maxEarlyWait.inWholeMilliseconds) {
            throw ScheduleNotDueException(command.ref, command.requestId, due, now)
        }
        if (early > 0) {
            delay(early)
        }
    }

    /**
     * [appendObserved], compensating [attempt] when the append throws: a backend throws only what surely wrote
     * nothing, such as a store it could not reach before sending the append (the retryable storage error).
     */
    private suspend fun appendCompensating(stream: EventStreamRecord, attempt: AttemptScope): AppendOutcome = try {
        appendObserved(stream, attempt)
    } catch (
        @Suppress("TooGenericExceptionCaught") // Compensates whatever failed, then rethrows it unchanged.
        failure: Throwable,
    ) {
        attempt.compensate(failure)
        throw failure
    }

    /** Appends [stream] of [attempt], observed: what it carries for delivery holds the append's trace. */
    private suspend fun appendObserved(stream: EventStreamRecord, attempt: AttemptScope): AppendOutcome {
        val appending = observer.appending(stream)
        return appending.observe {
            append(
                stream,
                attempt.transport(appending.trace, stream.rejection != null)?.let(TransportCodec::encode)
            ).also { appending.outcome(it.observedName) }
        }
    }

    /** The outcome of an attempt whose [stream] was [appended]; null on a version conflict, decided again. */
    private suspend fun settleAppend(
        appended: AppendOutcome,
        stream: EventStreamRecord,
        evolved: CachedState,
        attempt: AttemptScope,
        commandType: CommandType,
        requests: RequestCheck,
    ): CommandOutcome? = when (appended) {
        is AppendOutcome.Committed -> {
            requests.committed(stream, evolved)
            val rejection = stream.rejection
            if (rejection == null) {
                withContext(NonCancellable) { attempt.afterAppend(stream.version) }
            } else {
                // The refused decision's reservations are released, as for a rejection that writes nothing.
                attempt.compensate()
                rejected(stream.ref.type, commandType.name, rejection, attempt.command, withEvents = true)
            }
            CommandOutcome.Committed(
                stream,
                appended.position,
                evolved.state,
                if (rejection == null) attempt.results() else emptyMap(),
                evolved.deleted,
                evolved.schedules,
                evolved.first,
                rejection,
            )
        }

        is AppendOutcome.AlreadyCommitted -> {
            attempt.compensate()
            // Answered as the request-id lookup answers it: within the scope that sent it. With the recent
            // requests, a request id reused on another aggregate of the type reaches only this key.
            requests.duplicateOf(appended.existing)
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

    /**
     * [state] as decide receives it, for the purity check; null when it is fresh ([version] 0) and cannot be written
     * because a `lateinit` property is still unassigned, which the creation event's sourcing assigns. Any other failure
     * to write it fails the command.
     */
    private fun purityBaseline(state: Any, version: Int): String? = try {
        codec.stateText(state)
    } catch (failure: JacksonException) {
        val unassigned = generateSequence<Throwable>(failure) { it.cause }
            .any { it is UninitializedPropertyAccessException }
        if (version == 0 && unassigned) null else throw failure
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
        reach: CommandReach,
    ): Prepared {
        @Suppress("UNCHECKED_CAST") // The ref names the aggregate type, so the loaded state is this model's.
        val state = loaded.state as S
        val version = loaded.version
        val before = if (options.verifyDecidePurity) purityBaseline(state, version) else null
        // A command a client sends while deciding takes a request id derived from this one's, anew each attempt.
        val deciding = DecidingCommand(command.id)
        val decision = observer.deciding(command).observe {
            withContext(deciding) { decideAll(model, decider, scope, state, command.body) }
        }
        // Checked for every decision, a rejection that writes nothing included: its state is cached again as it is.
        if (before != null) {
            check(codec.stateText(state) == before) {
                "Deciding ${command.body.javaClass.name} changed the state of ${command.ref}: only events may change it."
            }
        }
        rejectedUnwritten(decision, model, commandType, version, command)?.let { return it }
        // A rejection's events are committed as any are, the rejection recorded on their stream.
        val rejection = (decision as? Decision.Rejected)?.let {
            RejectionCodes.qualified(it.rejection, model.type, commandType)
        }
        val domain = decision.events
        // A rejection commits its own events alone: what the refused decision scheduled is not.
        val schedules = if (rejection == null) scope.scheduleEvents() else emptyList()
        val events = domain + schedules
        if (events.isEmpty()) {
            return Prepared.Done(CommandOutcome.Unchanged(version))
        }
        check(domain.isNotEmpty() || version > 0) {
            "${command.ref} does not exist: a decision that only schedules needs an aggregate that exists."
        }
        // The decision's own time: what it read as `now` is the time its events are committed at.
        return appended(model, loaded, command, commandType, events, rejection, scope.now, reach)
    }

    /**
     * The stream [command] appends with [events] (a rejection's when [rejection] is not null) on [loaded] at
     * [commitTime], and the state after it; refused ([Prepared.Done.evolved]) when it leaves the aggregate out of its
     * caller's reach ([leaves]), checked once the events evolved the state, on the attributes the stream records.
     */
    @Suppress("LongParameterList") // The facts of the commit.
    private fun <S : Any> appended(
        model: AggregateModel<S>,
        loaded: CachedState,
        command: Command<*>,
        commandType: String,
        events: List<Any>,
        rejection: Rejection?,
        commitTime: Long,
        reach: CommandReach,
    ): Prepared {
        val version = loaded.version

        @Suppress("UNCHECKED_CAST") // The ref names the aggregate type, so the loaded state is this model's.
        val state = loaded.state as S
        val metadata = metadataAfter(model, loaded, command, events)
        // Read before the events evolve the state, which may change it in place.
        val before = if (reach.governed) loaded.declared(model) else emptyMap()
        val evolved = evolveDecided(model, state, events, command, commandType, version + 1, commitTime, metadata.scope)
        val declared = model.accessOf(evolved)
        leaves(command, reach, version, metadata.scope, before, declared)?.let {
            return Prepared.Done(it, evolved = true)
        }
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
            headers = rejection?.let { command.headers + EventStreamRecord.rejectionHeaders(it) } ?: command.headers,
            operator = command.operator,
            access = AccessValues.ofStream(codec.stored(declared), metadata.scope),
            // The attributes after this commit are of its own version, until a later change stamps them.
            accessVersion = version + 1,
        )
        val after = loaded.with(evolved, stream.version, metadata)
        after.access = declared
        if (stream.version == 1) {
            after.first = FirstCommit.of(stream)
        }
        return Prepared.Append(stream, after)
    }

    /**
     * [state] evolved by [events], decided on [command] and committed at [version] and [commitTime] in [scope]: an
     * evolve function that reads its commit reads it as the log will record it, so a rebuild reads the same.
     */
    @Suppress("LongParameterList") // The facts of the commit.
    private fun <S : Any> evolveDecided(
        model: AggregateModel<S>,
        state: S,
        events: List<Any>,
        command: Command<*>,
        commandType: String,
        version: Int,
        commitTime: Long,
        scope: Scope,
    ): S {
        val commit = if (model.readsCommits) {
            model.commitOf(
                command.ref,
                version,
                commitTime,
                command.id,
                command.requestId,
                commandType,
                scope,
                command.operator,
                events,
            )
        } else {
            null
        }
        return events.fold(state) { current, event -> model.evolve(current, event, commit) }
    }

    /**
     * The type name and JSON of [body], a command [model]'s aggregate schedules for [target]: the
     * model names its own commands, [commandTypes] those of other aggregate types. A body that holds its aggregate's
     * id (`@AggregateId`) names [target]'s.
     */
    private fun scheduledCommand(model: AggregateModel<*>, target: AggregateRef, body: Any): Pair<String, String> {
        val own = target.type == model.type
        val type = if (own) {
            model.commandType(body.javaClass).name
        } else {
            commandTypes.nameOf(target.type, body.javaClass) ?: throw Misuse.Argument(
                "${model.type} schedules ${body.javaClass.name} for $target, a command no Manifest of the service or " +
                    "its peers names.",
            )
        }
        val inBody = if (own) model.aggregateIdOf(body) else commandTypes.aggregateIdOf(target.type, body)
        if (inBody != null && inBody != target.id) {
            throw Misuse.Argument(
                "${model.type} schedules ${body.javaClass.name} for $target, but its body names the aggregate $inBody.",
            )
        }
        return type to codec.commandText(body)
    }

    /**
     * The aggregate's metadata after [command] commits [events] on [loaded]. Its scope: a command that
     * creates the aggregate gives it the caller's scope ([createdIn]); every other command carries the aggregate's
     * scope forward, and only the lifecycle events of transferring the owner or the space change it. Its deleted mark
     * changes only by its lifecycle events too.
     */
    private fun metadataAfter(
        model: AggregateModel<*>,
        loaded: CachedState,
        command: Command<*>,
        events: List<Any>,
    ): KernelLifecycle.Metadata {
        val before = if (loaded.version == 0) {
            loaded.metadata.copy(scope = createdIn(model, command))
        } else {
            loaded.metadata
        }
        return events.fold(before, KernelLifecycle::evolve)
    }

    /**
     * Appends [stream] with [transport]. An outcome that names this very append (the same command, request and commit
     * time at the same version) is this append committed: when the outcome is unknown, the version slot it targeted,
     * which only one stream can ever hold (the 9.3 rule), decides it (ours means committed, another stream of the
     * request a duplicate, another request a conflict, none stays unknown); and a duplicate the backend reports may be
     * its own retry of this append. Treating either as a duplicate would compensate what is a fact.
     * A conflict is checked against the request key before the command is decided again: a backend that
     * compares the version first reports a retry of the request at the same version as a conflict, and deciding it
     * again would run the decision's I/O twice.
     */
    private suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
        settled(stream, log.append(stream, transport))

    /** [duplicate] of [stream]: [stream] itself committed when it names this very append. */
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
            log.findByRequestId(stream.ref, stream.requestId)
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
     * The committed state of [ref] at [version], read-only (the state a subscription hands over when
     * the commit's shared state is gone, on a replay or after an ownership change): [snapshot] evolved with the log's
     * streams after it up to [version] when it fits, as on a cache miss, else the log replayed up to [version]. A
     * snapshot past [version], of another aggregate or not fitting is passed over. The state cache is not touched.
     *
     * The log is folded as it is read, never held whole, and at most [maxReplay] streams are folded:
     * a read that needs more, counted from the snapshot when it fits, else from version 1, is refused before it
     * reads the log.
     *
     * @return the state at [version] with the metadata the kernel keeps beside it (scope, deleted mark, schedules); null
     *   when the log does not hold [version].
     * @throws ReplayLimitExceededException when the state at [version] is more than [maxReplay] streams away.
     */
    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        version: Int,
        snapshot: SnapshotRecord? = null,
        maxReplay: Int = options.maxReplay,
    ): CachedState? =
        history.stateAt(model, ref, version, snapshot, maxReplay)

    /** The declared access attributes of [state], a state of [model], in their stored form ([EventCodec.accessOf]). */
    fun accessOf(model: AggregateModel<*>, state: Any): Map<String, Any?> = codec.accessOf(model, state)

    /**
     * The stream of [ref] at [version] as the log holds it, or null when it holds none: what a state read tells
     * beside a replayed state, the time and the operator of a commit. The state cache is not touched.
     */
    suspend fun streamAt(ref: AggregateRef, version: Int): EventStreamRecord? {
        require(version >= 1) { "A stream has a version from 1." }
        return log.read(ref, version, version).firstOrNull()
    }

    /**
     * The committed state of [ref] after [streams], its streams from version 1 in version order as the log holds them,
     * replayed without a snapshot (a snapshot rebuilt from the log, in place of one that does not fit).
     * The state cache is not touched.
     *
     * @return the state with the metadata the kernel keeps beside it; null when [streams] do not start at version 1
     *   or skip a version.
     */
    fun <S : Any> replayed(
        model: AggregateModel<S>,
        ref: AggregateRef,
        streams: List<EventStreamRecord>
    ): CachedState? =
        history.replayed(model, ref, streams)

    /**
     * The committed state after [stream], evolved from [base], the state of its aggregate at the version right before
     * it (a copy the caller hands over, which may be mutated; version 0 for the initial state), without reading the
     * log: the caller already holds [stream] as the log holds it, such as a subscription's entry.
     * The state cache is not touched.
     *
     * @return the state after [stream] with the metadata the kernel keeps beside it; null when [stream] is not the
     *   version right after [base]'s.
     */
    fun <S : Any> evolved(model: AggregateModel<S>, base: CachedState, stream: EventStreamRecord): CachedState? =
        history.evolved(model, base, stream)

    /**
     * The committed state of [ref] at [version], evolved from [base], its state at an earlier version that the caller
     * hands over to be evolved (it may be mutated: a copy, never a live instance), with the log's streams after it up
     * to [version] (a subscription behind the snapshot evolves the state it read for the previous
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
    ): CachedState? =
        history.stateAt(model, ref, version, base)

    /**
     * The committed state of [ref] as of [at], read-only: the state after the last stream
     * committed at or before [at] by its commit time. Commit times are epoch milliseconds, so [at] counts to its
     * millisecond. The log is read in version order and the first stream committed after [at] ends it: commit times
     * are taken to rise with the versions, which only a clock stepping back between two owners of the aggregate
     * breaks, and only by that step.
     *
     * [snapshot] serves as for a version (see the other [stateAt]) when its last stream committed at or before [at]:
     * it is evolved with the tail up to [at], so the log is not replayed from version 1. A snapshot committed after
     * [at], of another aggregate or not fitting is passed over. Every event is read as the model's current schema,
     * upgraded from the revision it was written in. The state cache is not touched.
     *
     * The log is folded as it is read, never held whole, and at most [maxReplay] streams are folded:
     * the read is refused as soon as it would fold one more.
     *
     * @return the state with the metadata the kernel keeps beside it (scope, deleted mark, schedules); null when no stream
     *   of [ref] had committed by [at].
     * @throws ReplayLimitExceededException when the state as of [at] is more than [maxReplay] streams away.
     */
    suspend fun <S : Any> stateAt(
        model: AggregateModel<S>,
        ref: AggregateRef,
        at: Instant,
        snapshot: SnapshotRecord? = null,
        maxReplay: Int = options.maxReplay,
    ): CachedState? =
        history.stateAt(model, ref, at, snapshot, maxReplay)

    /**
     * The commits of [ref] from version [fromVersion] up to [toVersion], at most [limit] of them, in version order,
     * read-only: each stream with its events, read as the model's current schema,
     * and the state and metadata after it. The state before [fromVersion] is the one [stateAt] gives for its version,
     * from [snapshot] and the tail when it fits, so the log is not replayed from version 1 then; reaching it folds at
     * most [maxReplay] streams. The state cache is not touched.
     *
     * @return the commits, empty when the log holds no version from [fromVersion] up to [toVersion].
     * @throws ReplayLimitExceededException when the state before [fromVersion] is more than [maxReplay] streams away.
     */
    @Suppress("LongParameterList")
    suspend fun <S : Any> trace(
        model: AggregateModel<S>,
        ref: AggregateRef,
        fromVersion: Int,
        toVersion: Int,
        limit: Int,
        snapshot: SnapshotRecord? = null,
        maxReplay: Int = options.maxReplay,
    ): List<CommitTrace> =
        history.trace(model, ref, fromVersion, toVersion, limit, snapshot, maxReplay)

    /**
     * The answer to [decision] when it is a rejection that writes nothing: one without events, at the [version] the
     * decision saw, or one of a creation, whose aggregate does not exist ([creationRejectedWithEvents]); null
     * otherwise. A local code is named by the deciding command's aggregate type from here on.
     */
    private fun rejectedUnwritten(
        decision: Decision,
        model: AggregateModel<*>,
        commandType: String,
        version: Int,
        command: Command<*>,
    ): Prepared.Done? {
        if (decision !is Decision.Rejected || decision.events.isNotEmpty() && version > 0) return null
        val rejection = RejectionCodes.qualified(decision.rejection, model.type, commandType)
        rejected(model.type, commandType, rejection, command, withEvents = false)
        val outcome = CommandOutcome.Rejected(
            rejection,
            version.takeIf { it > 0 },
            droppedEvents = decision.events.size
        )
        if (outcome.droppedEvents > 0) creationRejectedWithEvents(commandType)
        return Prepared.Done(outcome)
    }

    /**
     * Observes and logs that [command] of [commandType] on [type] was rejected: a business outcome, so at INFO
     * ([RejectionLog]).
     * [withEvents] is whether its events were committed with it.
     */
    private fun rejected(
        type: AggregateType,
        commandType: String,
        rejection: Rejection,
        command: Command<*>,
        withEvents: Boolean,
    ) {
        observer.observed(ObservedEvent.Rejected(type, commandType, rejection.code, withEvents))
        RejectionLog.rejected(
            command.ref,
            commandType,
            rejection.code,
            command.requestId,
            withEvents,
            rejection.message
        )
    }

    /** The command types whose creation was rejected with events, warned about once each. */
    private val creationRejections: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A creation of [commandType] rejected with events: its aggregate does not exist, so nothing is written and the
     * events are dropped ([CommandOutcome.Rejected.droppedEvents]), which the test DSL refuses; warned about once per
     * command type.
     */
    private fun creationRejectedWithEvents(commandType: String) {
        if (creationRejections.add(commandType)) {
            logger.warn {
                "A creation of $commandType was rejected with events: its aggregate does not exist, so they are " +
                    "dropped and nothing is written. Reject it without events."
            }
        }
    }

    /** The decision of [decider] on [state], its events followed by those of [model]'s after hooks. */
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
        declared(model, body, decided, decider.produces, "decide function")
        val afters = model.afterFunctions(body.javaClass)
        if (afters.isEmpty()) {
            return decision
        }
        val domain = decided + afters.flatMap { hook ->
            after(hook, scope, state, body).also { declared(model, body, it, hook.produces, "after hook") }
        }
        return if (domain.isEmpty()) Decision.NoOp else Decision.Events(domain)
    }

    /**
     * [events], which a [function] of [model] produced for [command]; an [UndeclaredEventException] when one is not of
     * [produces], the events it declares. Not checked when they are unknown.
     */
    private fun declared(
        model: AggregateModel<*>,
        command: Any,
        events: List<Any>,
        produces: Set<Class<*>>?,
        function: String,
    ) {
        if (produces == null) return
        val undeclared = events.firstOrNull { it.javaClass !in produces } ?: return
        val commandClass = command.javaClass
        throw UndeclaredEventException(
            model.type,
            commandClass,
            undeclared.javaClass,
            "${model.type}'s $function of ${commandClass.simpleName} produced ${undeclared.javaClass.simpleName}, " +
                "which it does not declare: neither its return type nor its produces lists it.",
        )
    }

    /** The decision of [decider], a thrown refusal answered as a returned one ([ThrownRejections]). */
    private suspend fun <S : Any> decide(
        decider: DecideFunction<S>,
        scope: DecideScope,
        state: S,
        body: Any,
    ): Decision = try {
        when (decider) {
            is DecideFunction.Sync -> decider.decide(scope, state, body)
            is DecideFunction.Suspending -> if (decider.blocking) {
                withContext(blocking) { decider.decide(scope, state, body) }
            } else {
                decider.decide(scope, state, body)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") thrown: Exception) {
        ThrownRejections.rejectionOf(thrown)?.let { Decision.Rejected(it, (thrown as? RejectedException)?.events.orEmpty()) }
            ?: throw thrown
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

    private sealed interface Prepared {
        /**
         * An outcome that appends nothing; [evolved] when it is a refusal once the decision's events evolved the
         * state ([leaves]), which may have changed it in place.
         */
        class Done(val outcome: CommandOutcome, val evolved: Boolean = false) : Prepared

        class Append(val stream: EventStreamRecord, val evolved: CachedState) : Prepared
    }

    @InternalWowApi
    companion object {
        /**
         * The requests kept per cached aggregate by default: at most 384 bytes each. A full ring
         * of 256 took 3 KB, and on a node that served sustained traffic the rings crowded the states out of the cache
         * by its bytes.
         */
        const val DEFAULT_RECENT_REQUESTS: Int = 32

        private val logger = KotlinLogging.logger {}
    }
}

/**
 * This command with its scope narrowed to what [model]'s ownership keeps: a tenant's aggregate neither
 * records nor checks an owner, only that part of the scope counts. An aggregate whose id is its owner's takes its id
 * for the owner of a command with none, or derived: a caller's other owner is kept, and refused ([admissible],
 * [creatable]).
 */
private fun Command<*>.narrowedTo(model: AggregateModel<*>): Command<*> {
    val owned = model.ownsById && (scope.ownerId == null || isDerived)
    val narrowed = if (owned) scope.copy(ownerId = ref.id) else model.ownership.scopeOf(scope)
    return if (narrowed == scope) this else copyWith(scope = narrowed)
}

/** The domain events this decision commits: none for a no-op, a rejection's own with it. */
private val Decision.events: List<Any>
    get() = when (this) {
        is Decision.Events -> events
        Decision.NoOp -> emptyList()
        is Decision.Rejected -> events
    }

/** Whether the model's aggregate's id is its owner's. */
private val AggregateModel<*>.ownsById: Boolean get() = idSource == AggregateIdSource.OWNER

/**
 * The scope of the aggregate [command] creates: the command's, but for an aggregate whose id is its owner's, whose
 * owner is its id whoever creates it ([creatable] refuses a caller of another owner).
 */
private fun createdIn(model: AggregateModel<*>, command: Command<*>): Scope =
    if (model.ownsById) command.scope.copy(ownerId = command.ref.id) else command.scope

/** This wait context when its caller waits beyond COMMITTED: then it goes with the append. */
private fun WaitContext?.beyondCommitted(): WaitContext? = this?.afterCommitted()?.takeIf { it.remaining.isNotEmpty() }

/**
 * Names the commands a decide function schedules for an aggregate of another type than its own: the
 * full type name (`context.aggregate.Command`) of the [command] class sent to an aggregate of [target], or null when
 * none is known. The runtime names them from the context's models and the Manifests of the service and its peers.
 */
@InternalWowApi
fun interface CommandTypes {
    fun nameOf(target: AggregateType, command: Class<*>): String?

    /**
     * The aggregate types that decide commands of the class [command]: those of the context's
     * models and of every contract the service knows (its Manifest and the peer contracts on its class path), a
     * mounted command left out. A saga's handler that leaves a command's ref out sends it to the one
     * type this gives; none is a missing contract, several a class it must address in full. None by default.
     */
    fun aggregatesOf(command: Class<*>): List<AggregateType> = emptyList()

    /**
     * The id of the aggregate of [target] that [command] acts on, as its body holds it (`@AggregateId`): read by the
     * context's model, or from the property of its JSON body a contract names. Null when its command type holds none
     * there, or when it leaves it null (a creation). None by default.
     */
    fun aggregateIdOf(target: AggregateType, command: Any): String? = null

    @InternalWowApi
    companion object {
        /** Names none: a decide function schedules commands of its own aggregate type only. */
        @JvmField
        val NONE: CommandTypes = CommandTypes { _, _ -> null }

        /**
         * Names a command by its class's `@Name`, else its simple name, in its target's type (`context.aggregate.Command`),
         * the name a model gives a command unless it says otherwise: for a saga outside a node, in a domain module's test.
         */
        @JvmField
        val SIMPLE_NAMES: CommandTypes = CommandTypes { target, command -> "$target.${typeNameOf(command)}" }
    }
}

/**
 * Whether this command was derived by the server's own code rather than sent by a caller: a saga's,
 * an `@OnRejected` compensation, a timer's, or one another context sent through its command topic. Such a command
 * belongs to a chain, so it carries a [Command.causation]; admission gives a caller's none (it is its own root).
 */
@InternalWowApi
val Command<*>.isDerived: Boolean get() = causation != null

/** How an execution ended, as its observation names it. */
internal val CommandOutcome.observedName: ObservedOutcome
    get() = when (this) {
        is CommandOutcome.Committed -> if (rejection == null) ObservedOutcome.COMMITTED else ObservedOutcome.REJECTED
        is CommandOutcome.AlreadyCommitted -> ObservedOutcome.DUPLICATE
        is CommandOutcome.Unchanged -> ObservedOutcome.UNCHANGED
        is CommandOutcome.Rejected -> ObservedOutcome.REJECTED
        is CommandOutcome.Contended -> ObservedOutcome.CONTENDED
        is CommandOutcome.Unknown -> ObservedOutcome.UNKNOWN
    }

/** How an append ended, as its observation names it. */
internal val AppendOutcome.observedName: ObservedOutcome
    get() = when (this) {
        is AppendOutcome.Committed -> ObservedOutcome.COMMITTED
        is AppendOutcome.AlreadyCommitted -> ObservedOutcome.DUPLICATE
        is AppendOutcome.Conflict -> ObservedOutcome.CONFLICT
        is AppendOutcome.Unknown -> ObservedOutcome.UNKNOWN
        is AppendOutcome.Rejected -> ObservedOutcome.REJECTED
    }
