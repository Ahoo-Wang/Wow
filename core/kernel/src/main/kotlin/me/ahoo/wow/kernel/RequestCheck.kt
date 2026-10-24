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

import kotlinx.coroutines.flow.firstOrNull
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * How one execution makes sure its request is not committed already (decision D46, revised by D261): from the
 * aggregate's recent requests where they answer, else by looking the request id up, at most once per execution.
 *
 * - A request the loaded state's recent requests hold is confirmed by reading its stream at the version they name
 *   (a hash may collide) and answered as a duplicate.
 * - On a miss, only a pure decide ([DecideFunction.Sync] with only [AfterFunction.Sync] after hooks) goes on
 *   without a lookup (C2): deciding again costs nothing outside the log, and an append meets the request key, the
 *   permanent backstop. A decide that does I/O goes on without one only when the recent requests hold the
 *   aggregate's whole history and this node holds the partition's lease ([ExecutionLease.HELD]): the request is
 *   then surely not the aggregate's (only a request id reused on another aggregate of the type, the caller's error
 *   by D55, still reaches the backstop).
 * - An outcome that appends nothing (an admission refusal, a rejection, an unchanged one) never meets the request
 *   key, so the request id is looked up before it is answered (C1): a retry is answered as a duplicate, as before.
 */
internal class RequestCheck(
    private val log: EventLog,
    private val states: StateCache,
    /** The requests kept per cached aggregate; 0 keeps none, and every request id is looked up first (D46). */
    private val recentRequests: Int,
    private val model: AggregateModel<*>,
    decider: DecideFunction<*>,
    private val command: Command<*>,
    lease: ExecutionLease?,
    /** The answer to the command when [EventStreamRecord] committed its request (D46, D94, D55). */
    private val duplicate: (EventStreamRecord) -> CommandOutcome,
) {
    /** Whether the decide function and its after hooks do no I/O ([DecideFunction.Sync], [AfterFunction.Sync]). */
    val pure = decider is DecideFunction.Sync &&
        model.afterFunctions(command.body.javaClass).all { it is AfterFunction.Sync }
    private val leaseHeld = lease?.held == true

    /** The aggregate the command is for. */
    val ref = command.ref

    /** Whether this execution's states are cached: not when it runs without the partition's lease (C4). */
    val caching = lease?.held != false

    /** Whether the request id was looked up in this execution and found nothing. */
    private var lookedUp = false

    /**
     * The duplicate answer, before the command waits for its slot or loads anything, when the request id must be
     * looked up first: without recent requests ([recentRequests] 0, D46), and for a scheduled command, whose duplicate
     * is answered without waiting for the slot (D198).
     */
    suspend fun beforeWaiting(): CommandOutcome? =
        if (recentRequests == 0 || command.schedule != null) lookUp() else null

    /** The duplicate answer when the request id is committed; null when it is not, remembered. */
    suspend fun lookUp(): CommandOutcome? {
        val existing = log.findByRequestId(model.type, command.ref.tenantId, command.requestId)
        lookedUp = existing == null
        return existing?.let { duplicate(it) }
    }

    /**
     * The duplicate answer when [loaded]'s recent requests hold the request, or a lookup finds it when it must;
     * null to decide. [loaded] stays cached when the command is answered from it.
     */
    suspend fun beforeDeciding(loaded: CachedState): CommandOutcome? {
        val recent = loaded.requests
        val collided = recent?.versionOf(command.requestId)?.let { version ->
            val existing = log.read(command.ref, version, version).firstOrNull()
            if (existing?.requestId == command.requestId) {
                return answered(loaded, duplicate(existing))
            }
            true
        } ?: false
        val authoritative = leaseHeld && recent?.complete == true && !collided
        if (lookedUp || pure || authoritative) {
            return null
        }
        return lookUp()?.let { answered(loaded, it) }
    }

    /** The aggregate's cached state, taken out; none when this execution runs without the lease (C4), dropped. */
    fun cached(): CachedState? = states.take(ref)?.takeIf { caching }

    /**
     * How many request keys before its snapshot a load reads with the aggregate's tail (C3): one more than are kept,
     * so that a full window tells a truncated history.
     */
    val earlier: Int get() = if (recentRequests == 0) 0 else recentRequests + 1

    /** [loaded], read with [tail] (a replay's tail holds every stream), with the recent requests derived from it. */
    fun derived(loaded: CachedState, tail: AggregateTail): CachedState =
        loaded.also { it.requests = RecentRequests.of(recentRequests, tail) }

    /** Caches [evolved], the state [stream] committed, its recent requests holding the request now. */
    fun committed(stream: EventStreamRecord, evolved: CachedState) {
        if (caching) {
            evolved.requests?.add(stream.requestId, stream.version)
            states.put(ref, evolved)
        }
    }

    /**
     * [outcome], decided on [loaded] without appending, keeping [loaded] cached: decided without evolving, the state
     * is as loaded (decide purity is the user's contract, F4). A duplicate instead when the request is committed
     * (C1): nothing appended, so the request key never saw it. Null when [loaded] came from the cache ([cached]) and
     * the log has moved past it: the command is decided again on a reloaded state.
     */
    suspend fun standing(loaded: CachedState, cached: Boolean, outcome: CommandOutcome): CommandOutcome? {
        refused(loaded)?.let { return it }
        if (cached && behindLog(loaded.version)) {
            return null
        }
        return answered(loaded, outcome)
    }

    /**
     * Whether the log holds a stream of the aggregate after [version], the version of a cached state a command was
     * rejected on or changed nothing on. Without fencing (decision D45), another node may have committed to the
     * aggregate while its owner's cache held it: a command that appends meets that stream as a version conflict, but
     * one that appends nothing must read the next version slot, or it answers from a state the log has moved past.
     */
    private suspend fun behindLog(version: Int): Boolean = log.read(ref, version + 1, version + 1).firstOrNull() != null

    /** The duplicate answer to an outcome that appends nothing, decided on [loaded], when the request is committed. */
    private suspend fun refused(loaded: CachedState): CommandOutcome? =
        if (lookedUp) null else lookUp()?.let { answered(loaded, it) }

    /** [outcome], answered without deciding on [loaded], which stays as it was cached. */
    private fun answered(loaded: CachedState, outcome: CommandOutcome): CommandOutcome {
        if (caching) {
            states.put(command.ref, loaded)
        }
        return outcome
    }
}
