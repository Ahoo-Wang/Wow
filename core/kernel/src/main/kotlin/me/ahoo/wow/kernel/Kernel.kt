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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.wait.WaitContext
import java.time.Instant
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
 * [KernelOptions.verifyDecidePurity] fails a command whose decide function or after hook changed the state
 * ([Preparation]).
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
 * later command and changed only by the lifecycle commands transferring the owner or the space (see [Preparation.metadataAfter]).
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
 * alone (on an aggregate that exists). The command a schedule sends is stored by its type name and JSON
 * ([AttemptScopes]). Every aggregate also decides the
 * system commands `wow.schedule.Set` and `wow.schedule.Cancel` a saga derives ([KernelLifecycle]); the kernel keeps
 * the rules of the schedules an aggregate holds beside its state ([CachedState.schedules]).
 *
 * A command a schedule sent ([Command.schedule]) is not decided before the time its slot was due by this kernel's
 * [clock]: a node whose clock is behind the firing node's waits until then, so a schedule never fires
 * early. It waits at most [KernelOptions.maxEarlyWait], holding the aggregate's queue meanwhile: a command due later than that fails
 * at once with [ScheduleNotDueException], and its firing runner sends it again later.
 *
 * A decide function takes its ids from [ids] ([AttemptScopes]).
 *
 * A decide function or after hook marked `@Blocking` runs on [blocking] ([Decisions]).
 *
 * [observer] sees each execution, decision and append; what an append carries for delivery holds the
 * append's trace ([Transport.trace]), so the subscriptions that handle it continue the command's trace.
 */
@InternalWowApi
class Kernel(
    private val log: EventLog,
    private val codec: EventCodec = EventCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    reservations: ReservationStore? = null,
    private val states: StateCache = StateCache.None,
    snapshots: SnapshotStore? = null,
    commandTypes: CommandTypes = CommandTypes.NONE,
    ids: Ids = IdGenerators.DEFAULT,
    private val observer: Observer = Observer.NONE,
    blocking: CoroutineContext = BlockingDispatcher.dispatcher,
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
    policies: WritePolicies = WritePolicies.NONE,
) {
    /** Where a state comes from on a cache miss, and how streams fold into it. */
    private val source = StateSourcing(log, codec, observer, snapshots, snapshotTailReaders)

    /** The reads of a committed state at a version or a time, and of its history. */
    private val history = StateHistory(source, log, codec)

    /** The append of a decided stream, and what its outcome means for the command. */
    private val appends = Appends(log, observer)

    /** A decision on a loaded state, turned into what it commits. */
    private val preparation = Preparation(codec, observer, options, Decisions(blocking))

    /** How each attempt's scope is opened. */
    private val scopes = AttemptScopes(ids, clock, reservations, options, observer, commandTypes, codec)

    /** Each command's reach and request check, set up alike alone and in a batch. */
    private val checks = RequestChecks(log, states, options.recentRequests, policies, codec)

    /** A batch of an aggregate's commands: the ones it takes, and their streams committed together. */
    private val batching = Batching(log, states, checks, source, preparation, appends, scopes, observer)

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
        val members = if (recentRequests > 0) batching.membersOf(model, queued, appending) else BatchMembers()
        if (members.streams.isEmpty()) {
            return listOf(alone(model, queued.first(), appending)) + List(queued.size - 1) { BatchOutcome.Again }
        }
        val outcomes = batching.commitBatch(members) + List(queued.size - members.streams.size) { BatchOutcome.Again }
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
        val requests = checks.of(model, decider, command, commandType, lease)
        val reach = requests.reach
        requests.beforeWaiting()?.let { return it }
        awaitScheduled(command)
        val waitBeyondCommitted = wait.beyondCommitted()
        val encode = scopes.encoder(model)
        var attempts = 0
        while (attempts < maxAttempts) {
            val (loaded, cached) = source.load(model, requests)
            // Checked after every load: a reload may hold the request another node committed meanwhile.
            requests.beforeDeciding(loaded)?.let { return it }
            admissible(decider, command, loaded, reach)?.let {
                return requests.standing(loaded, cached, it) ?: continue
            }
            val attempt = attemptOn(command, loaded, waitBeyondCommitted, encode)
            val prepared = try {
                preparation.prepare(model, decider, attempt, loaded, command, commandType.name, reach)
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
            val appended = withContext(NonCancellable) { appends.appendCompensating(stream, attempt) }
            appends.settleAppend(appended, stream, evolved, attempt, commandType, requests)?.let { return it }
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
     * Waits for the slot [command] was sent for, when a schedule sent it ([awaitDue]); one no schedule sent goes on at
     * once. Inlined, so a command that is not scheduled makes no call for it.
     */
    @Suppress("NOTHING_TO_INLINE")
    private suspend inline fun awaitScheduled(command: Command<*>) {
        command.schedule?.let { awaitDue(command, it.due, clock()) }
    }

    /**
     * An attempt of [command] on [loaded], at this kernel's time read once, as for any command ([AttemptScopes.openDue]);
     * a scheduled command that reading finds before its slot's due time is opened late ([openLate]). Inlined, so a
     * command that is not scheduled makes no call for it.
     */
    @Suppress("NOTHING_TO_INLINE")
    private suspend inline fun attemptOn(
        command: Command<*>,
        loaded: CachedState,
        wait: WaitContext?,
        noinline encode: (AggregateRef, Any) -> Pair<String, String>,
    ): AttemptScope = scopes.openDue(command, loaded, wait, encode) ?: openLate(command, loaded, wait, encode)

    /**
     * An attempt of [command] on [loaded], sent for a slot that the clock read for the attempt found not yet due (it
     * stepped back while the state loaded): opened once it is due again ([awaitDue]), at the reading that found it due.
     */
    private suspend fun openLate(
        command: Command<*>,
        loaded: CachedState,
        wait: WaitContext?,
        encode: (AggregateRef, Any) -> Pair<String, String>,
    ): AttemptScope {
        val due = checkNotNull(command.schedule) { "Only a scheduled command is opened late." }.due
        return scopes.open(command, loaded, wait, encode, awaitDue(command, due, clock()))
    }

    /**
     * This kernel's time once [due], the time the slot [command] was sent for is due, has come on its clock, read
     * [now] at first: a schedule never fires before its time here, whatever the firing node's clock said. One due
     * later than [maxEarlyWait] from now is not waited for, holding the aggregate's queue: it throws
     * [ScheduleNotDueException], and its firing runner sends it again later.
     *
     * A wait runs on the coroutine's clock, which need not advance as this kernel's does (a wall clock slewed or
     * stepped back), so the clock is read again after each wait, and the time returned is the reading that found the
     * slot due: an attempt opened at it never decides, nor commits, before the slot's due time.
     */
    private suspend fun awaitDue(command: Command<*>, due: Long, now: Long): Long {
        var time = now
        while (time < due) {
            if (due - time > options.maxEarlyWait.inWholeMilliseconds) {
                throw ScheduleNotDueException(command.ref, command.requestId, due, time)
            }
            delay(due - time)
            time = clock()
        }
        return time
    }

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

    /**
     * The state [snapshot] holds, as a base to evolve with the log's streams after it (the [stateAt] that takes a
     * base), or null when it does not fit [model]: of another aggregate type, written at another state revision or for
     * another state schema, or not decodable. Nothing is read; each call decodes a fresh copy.
     */
    fun <S : Any> snapshotState(model: AggregateModel<S>, snapshot: SnapshotRecord): CachedState? =
        source.stateOf(model, snapshot)

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
