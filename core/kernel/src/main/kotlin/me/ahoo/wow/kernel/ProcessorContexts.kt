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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.timer.Timers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** The context of a projection's handler for one execution (design 01 §1). */
data class ExecutionProjectContext(override val executionId: ExecutionId) : ProjectContext

/** The context of a reactor's handler for one execution (design 01 §1). */
data class ExecutionReactContext(override val executionId: ExecutionId) : ReactContext

/**
 * The context of a saga's handler for one execution of the commit [event] (design 01 §1, design 04 §1). Each
 * execution, a retry included, gets a new one, so the n-th [command] of every execution of the same commit by the
 * same subscription is the same request: handling the commit again derives the same commands, which the kernel does
 * not decide twice (it deduplicates by request id). A domain module's tests build one to call a saga directly.
 *
 * Its schedules (decision D166) are recorded in [schedules], the last change of each target and key, each with the id
 * [Timers.sagaTimerId] gives the call, so handling the commit again records the same: the runtime writes them to the
 * timer store before the saga's checkpoint passes the commit. `after` counts from the commit's time.
 *
 * Its [ids] are the node's generators (decision D171); a domain module's test gives fixed ones.
 */
class ExecutionSagaContext(
    override val executionId: ExecutionId,
    private val event: StateEvent<*>,
    override val ids: Ids = IdGenerators.DEFAULT,
) : SagaContext {
    private val calls = AtomicInteger()
    private val scheduleCalls = AtomicInteger()
    private val scheduled = LinkedHashMap<Pair<AggregateRef, String>, SagaSchedule>()

    /** The schedules this execution changed, the last change of each target and key, in the order of those changes. */
    val schedules: List<SagaSchedule> get() = synchronized(scheduled) { scheduled.values.toList() }

    init {
        require(event.ref == executionId.ref && event.version == executionId.version) {
            "Execution $executionId is not of the commit ${event.id}."
        }
    }

    /**
     * A command to [ref] asking [body]: its id and request id are [derivedId] of this execution and the call's index,
     * and its causation links it to the commit (`ref@version`) and to the root command of the chain; its operator is
     * the commit's, the root command's (decision D163), and so is its scope (decision D189): the owner and space of
     * the commit, so an aggregate it creates belongs to them, and the kernel guards one it changes as for them (D94).
     */
    override fun <C : Any> command(ref: AggregateRef, body: C): Command<C> {
        val id = derivedId(executionId, calls.getAndIncrement())
        return Command(
            id = id,
            requestId = id,
            ref = ref,
            body = body,
            scope = event.scope,
            causation = Causation(event.correlationId, event.id),
            operator = event.operator,
        )
    }

    override fun schedule(key: String, at: Instant, target: AggregateRef, command: Any) =
        record(SagaSchedule.Put(nextId(), key, target, at.toEpochMilli(), command))

    override fun schedule(key: String, after: Duration, target: AggregateRef, command: Any) {
        require(!after.isNegative()) { "A schedule is not due before the commit." }
        record(SagaSchedule.Put(nextId(), key, target, event.commitTime + after.inWholeMilliseconds, command))
    }

    override fun cancel(key: String, target: AggregateRef) = record(SagaSchedule.Cancel(nextId(), key, target))

    private fun nextId(): String = Timers.sagaTimerId(executionId, scheduleCalls.getAndIncrement())

    private fun record(schedule: SagaSchedule) {
        require(schedule.key.isNotBlank()) { "A schedule needs a key." }
        synchronized(scheduled) {
            val key = schedule.target to schedule.key
            scheduled.remove(key)
            scheduled[key] = schedule
        }
    }

    companion object {
        /**
         * The id, and request id, of the [index]-th command (from 0) derived by [execution]: a name-based UUID of
         * `execution#index`, stable across every execution of the same commit by the same subscription.
         */
        @JvmStatic
        fun derivedId(execution: ExecutionId, index: Int): String {
            require(index >= 0) { "A call index is never negative." }
            return UUID.nameUUIDFromBytes("$execution#$index".toByteArray(Charsets.UTF_8)).toString()
        }
    }
}

/**
 * A schedule a saga changed in one execution (decision D166): its timer [id] (`executionId#n`), its [key] among the
 * saga's schedules of [target].
 */
sealed interface SagaSchedule {
    val id: String
    val key: String
    val target: AggregateRef

    /** Sends [command] to [target] at [dueAt] (epoch milliseconds). */
    data class Put(
        override val id: String,
        override val key: String,
        override val target: AggregateRef,
        val dueAt: Long,
        val command: Any,
    ) : SagaSchedule

    /** Cancels the saga's schedule of [target] under [key]. */
    data class Cancel(override val id: String, override val key: String, override val target: AggregateRef) :
        SagaSchedule
}

/**
 * The context a handler of [kind] gets for the execution of [event] by the subscription [subscription]: its
 * [ExecutionId] is (subscription, aggregate, version); a saga's takes its ids from [ids].
 */
fun contextOf(
    kind: FunctionKind,
    subscription: String,
    event: StateEvent<*>,
    ids: Ids = IdGenerators.DEFAULT,
): SubscriptionContext {
    val execution = ExecutionId(subscription, event.ref, event.version)
    return when (kind) {
        FunctionKind.PROJECT -> ExecutionProjectContext(execution)
        FunctionKind.REACT -> ExecutionReactContext(execution)
        FunctionKind.SAGA -> ExecutionSagaContext(execution, event, ids)
        else -> throw IllegalArgumentException("A $kind handler is not a processor's.")
    }
}
