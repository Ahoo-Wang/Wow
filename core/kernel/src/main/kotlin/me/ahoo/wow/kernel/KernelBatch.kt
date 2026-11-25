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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.call.DecidingCommand
import me.ahoo.wow.spi.log.Append
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.ExecutionLease
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.TraceContext
import me.ahoo.wow.spi.wait.WaitContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A command an aggregate's lane hands to [Kernel.executeBatch], with the wait context the runtime built for it and the
 * trace of its caller's coroutine, none when it has none: a batch runs in its first command's coroutine, and each
 * command's execution is observed in its own caller's trace, as when it runs alone.
 */
@InternalWowApi
class QueuedCommand(val command: Command<*>, val wait: WaitContext? = null, val trace: TraceContext? = null)

/** What [Kernel.executeBatch] did with one command of a batch. */
@InternalWowApi
sealed interface BatchOutcome {
    /** The command's outcome, as [Kernel.execute] would have answered it. */
    @InternalWowApi
    class Done(val outcome: CommandOutcome) : BatchOutcome

    /** The command failed with [failure], as [Kernel.execute] would have thrown it. */
    @InternalWowApi
    class Failed(val failure: Throwable) : BatchOutcome

    /**
     * Not executed: a stream before it in the batch did not commit, or it could not join the batch. The lane runs it
     * again, in order.
     */
    @InternalWowApi
    data object Again : BatchOutcome
}

/**
 * How the [Kernel] runs a batch of an aggregate's commands ([Kernel.executeBatch]): the commands the batch takes, each
 * decided on the state the ones before it left ([membersOf]), and their streams appended together and settled in order
 * ([commitBatch]).
 *
 * Each command's execution is observed as a command alone's is, in its caller's trace ([executing]): from the start of
 * its decision in the batch to its outcome, its decision and its stream's append the execution's own stages. The batch
 * ends those observations for each command it settles. A command it hands back to its lane ([BatchOutcome.Again]) is
 * decided again, and observed by the run that settles it: the batch drops its observations without ending them, and
 * observes nothing of a decision it did not keep (one that appends nothing, or fails). A command handed back once its
 * stream was decided, behind a stream that did not commit, leaves that decision's observation ended and its
 * execution's dropped: a tracer exports that decision's span without its parent. The first command's execution is
 * [Kernel.executeBatch]'s, which goes on observing it when it runs the command again alone.
 */
internal class Batching(
    private val log: EventLog,
    private val states: StateCache,
    private val checks: RequestChecks,
    private val source: StateSourcing,
    private val preparation: Preparation,
    private val appends: Appends,
    private val scopes: AttemptScopes,
    private val observer: Observer,
) {
    /**
     * The batch [queued] starts: the stream of each command it takes, decided in order on the state the ones before
     * it left. Empty when the first command cannot start one: its state, if loaded, is cached again unchanged.
     */
    internal suspend fun <S : Any> membersOf(
        model: AggregateModel<S>,
        queued: List<QueuedCommand>,
        appending: ((EventStreamRecord, CachedState) -> Unit)?,
        leading: Observation,
    ): BatchMembers {
        val lease = currentCoroutineContext()[ExecutionLease]
        val members = BatchMembers()
        var state: CachedState? = null
        var caching = false
        // The first command's execution is observed from the start of the batch; any other's from its decision.
        var observed: Observation? = leading
        for (entry in queued) {
            val command = entry.command.narrowedTo(model)
            val decider = model.decider(command.body.javaClass) ?: break
            val commandType = model.commandType(command.body.javaClass)
            val requests = checks.of(model, decider, command, commandType, lease)
            val reach = requests.reach
            if (!joins(requests, command)) break
            val current = state ?: source.load(model, requests).first.also {
                state = it
                caching = requests.caching
            }
            val member = decided(model, decider, entry, command, commandType, requests, current, members, reach, observed)
                ?: break
            observed = null
            members.streams += member
            // Before the next command evolves the same (mutable) state further.
            appending?.invoke(member.stream, member.evolved)
            state = member.evolved
            // Its after-append actions run once the batch commits, and read the state it was decided on: no command
            // after it in this batch evolves that state further.
            if (member.attempt.readsAfterAppend) break
        }
        if (members.streams.isEmpty() && caching) {
            putBackUnchanged(members, queued.first().command.ref, state)
        }
        return members
    }

    /** A command with I/O to decide, or sent for a schedule, runs alone: it ends the batch, or is the first. */
    private fun joins(requests: RequestCheck, command: Command<*>): Boolean =
        requests.pure && command.schedule == null

    /**
     * Caches [state] of [ref] again, as it was taken, when no member of [members] evolved it (C4: only under the
     * lease), unless it is the initial state of an aggregate not yet created; the first command then runs alone. A
     * first command that failed deciding may have evolved it part way: it is dropped.
     */
    private fun putBackUnchanged(members: BatchMembers, ref: AggregateRef, state: CachedState?) {
        if (members.tainted) return
        state?.takeIf { it.version > 0 }?.let { states.put(ref, it) }
    }

    /**
     * [command] decided on [current] as a member of [members]' batch, in its execution's observation: [executing], or
     * one started now in its caller's trace. Null when it cannot join it: a request the aggregate or the batch holds,
     * one refused before deciding, or one that fails, appends nothing or is refused deciding (each decided again alone,
     * at no cost: it is pure); the observation of its execution started here is then dropped.
     */
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
        executing: Observation?,
    ): BatchMember? {
        val repeated = current.recentVersionOf(command.requestId) != null ||
            members.streams.any { it.stream.requestId == command.requestId }
        // A creation that declares its time (`@OccurredAt`) is decided alone, where that time is checked.
        val declares = current.version == 0 && model.occurredAtOf(command.body) != null
        if (repeated || declares || admissible(decider, command, current, reach) != null) return null
        val encode = scopes.encoder(model)
        val wait = entry.wait.beyondCommitted()
        val observation = executing ?: executing(entry, commandType.name)
        val attempt = scopes.open(command, current, wait, encode)
        val prepared = try {
            observation.within {
                preparation.prepare(
                    model,
                    decider,
                    attempt,
                    current,
                    command,
                    commandType.name,
                    reach,
                    // A member is decided once here: decided again, it runs alone, through execute.
                    DecidingCommand(command.ref, command.requestId),
                    trial = true,
                )
            }
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
        return BatchMember(command, commandType, requests, attempt, prepared.stream, prepared.evolved, observation)
    }

    /**
     * The observation of [entry]'s execution, observed as [commandType]: started in its caller's trace
     * ([QueuedCommand.trace]) when the batch runs in another's, so its stages, and what its stream carries for
     * delivery, are of its own request's trace.
     */
    internal suspend fun executing(entry: QueuedCommand, commandType: String): Observation {
        val trace = entry.trace
        return if (trace == null || currentCoroutineContext()[TraceContext] === trace) {
            observer.executing(entry.command, commandType)
        } else {
            withContext(trace) { observer.executing(entry.command, commandType) }
        }
    }

    /**
     * Appends the batch's streams and settles each: the outcomes of [members], in order. Each stream's append is
     * observed in its command's execution, from before the batch's append until its outcome is settled, the outcome
     * it is told, as a command alone's is.
     */
    internal suspend fun commitBatch(members: BatchMembers): List<BatchOutcome> = withContext(NonCancellable) {
        val batch = members.streams
        val observations = batch.map { member -> member.executing.within { observer.appending(member.stream) } }
        val appended = try {
            log.appendAll(
                batch.mapIndexed { at, member ->
                    val transport = member.attempt.transport(observations[at].trace, member.stream.rejection != null)
                    Append.of(member.stream, transport?.let(TransportCodec::encode))
                }
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") // Compensates whatever failed; each command fails with it.
            failure: Exception,
        ) {
            batch.forEachIndexed { at, member ->
                member.attempt.compensate(failure)
                observations[at].ended(failure)
                member.executing.ended(failure)
            }
            states.take(batch.first().stream.ref)
            return@withContext batch.map { BatchOutcome.Failed(failure) }
        }
        var stopped = false
        batch.mapIndexed { at, member ->
            if (stopped) {
                // Not appended: handed back, and observed by the run that settles it.
                member.attempt.compensate()
                return@mapIndexed BatchOutcome.Again
            }
            val settled = appends.settled(member.stream, appended[at])
            if (settled !is AppendOutcome.Committed) {
                stopped = true
            }
            // A stream that conflicts is decided again: the first command's at once, alone, in the same execution's
            // observation (an attempt that conflicted, as a command alone's), any other's handed back.
            if (settled !is AppendOutcome.Conflict || at == 0) {
                observations[at].run {
                    outcome(settled.observedName)
                    end()
                }
            }
            // Its outcome, its execution's observation ended with it; a conflict is decided again, and observed by the
            // run that settles it.
            member.executing.within {
                try {
                    appends.settleAppend(settled, member)?.let { outcome ->
                        member.executing.outcome(outcome.observedName)
                        member.executing.end()
                        BatchOutcome.Done(outcome)
                    } ?: BatchOutcome.Again
                } catch (
                    @Suppress("TooGenericExceptionCaught") // A refused stream fails its own command, as in execute.
                    failure: Exception,
                ) {
                    member.executing.ended(failure)
                    BatchOutcome.Failed(failure)
                }
            }
        }.also {
            // A stream after the last committed one evolved the cached state: it is dropped, and reloaded.
            if (stopped) states.take(batch.first().stream.ref)
        }
    }
}

/**
 * [Appends.settleAppend] of [member]'s stream, which its batch's append [appended]. A tail call, so it adds no
 * continuation per command.
 */
private suspend fun Appends.settleAppend(appended: AppendOutcome, member: BatchMember): CommandOutcome? =
    settleAppend(appended, member.stream, member.evolved, member.attempt, member.commandType, member.requests)

/**
 * Runs [block] in this observation's context, as [me.ahoo.wow.spi.observation.observe] does, neither telling it what
 * [block] fails with nor ending it: the caller ends it, or drops it. For an observation without a context (one nobody
 * traces) it only runs [block].
 */
internal suspend inline fun <T> Observation.within(crossinline block: suspend () -> T): T {
    val context = context
    return if (context === EmptyCoroutineContext) block() else withContext(context) { block() }
}

/** Tells this observation it failed with [failure], and ends it, as [me.ahoo.wow.spi.observation.observe] does. */
internal fun Observation.ended(failure: Throwable) {
    failed(failure)
    end()
}

/** The streams a batch takes, in order. */
internal class BatchMembers {
    val streams = ArrayList<BatchMember>()

    /** Whether a command failed deciding after the state was taken: it may have evolved it part way. */
    var tainted = false
}

/** One command of a batch, decided: its stream, the state after it, and the observation of its execution. */
internal class BatchMember(
    val command: Command<*>,
    val commandType: CommandType,
    val requests: RequestCheck,
    val attempt: AttemptScope,
    val stream: EventStreamRecord,
    val evolved: CachedState,
    val executing: Observation,
)
