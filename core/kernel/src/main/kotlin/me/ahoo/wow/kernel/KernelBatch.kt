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
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.TransportCodec
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.WaitContext

/** A command an aggregate's lane hands to [Kernel.executeBatch], with the wait context the runtime built for it. */
@InternalWowApi
class QueuedCommand(val command: Command<*>, val wait: WaitContext? = null)

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
    ): BatchMembers {
        val lease = currentCoroutineContext()[ExecutionLease]
        val members = BatchMembers()
        var state: CachedState? = null
        var caching = false
        for (entry in queued) {
            val command = entry.command.narrowedTo(model)
            val decider = model.decider(command.body.javaClass) ?: break
            val commandType = model.commandType(command.body.javaClass)
            val requests = checks.of(model, decider, command, commandType, lease)
            val reach = requests.reach
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
        val repeated = current.recentVersionOf(command.requestId) != null ||
            members.streams.any { it.stream.requestId == command.requestId }
        if (repeated || admissible(decider, command, current, reach) != null) return null
        val encode = scopes.encoder(model)
        val wait = entry.wait.beyondCommitted()
        val attempt = scopes.open(command, current, wait, encode)
        val prepared = try {
            preparation.prepare(model, decider, attempt, current, command, commandType.name, reach)
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
    internal suspend fun commitBatch(members: BatchMembers): List<BatchOutcome> = withContext(NonCancellable) {
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
            val settled = appends.settled(member.stream, appended[at])
            if (settled !is AppendOutcome.Committed) {
                stopped = true
            }
            try {
                appends.settleAppend(settled, member.stream, member.evolved, member.attempt, member.commandType, member.requests)
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
}

/** The streams a batch takes, in order. */
internal class BatchMembers {
    val streams = ArrayList<BatchMember>()

    /** Whether a command failed deciding after the state was taken: it may have evolved it part way. */
    var tainted = false
}

/** One command of a batch, decided: its stream and the state after it. */
internal class BatchMember(
    val command: Command<*>,
    val commandType: CommandType,
    val requests: RequestCheck,
    val attempt: AttemptScope,
    val stream: EventStreamRecord,
    val evolved: CachedState,
)
