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

package me.ahoo.wow.runtime.timer

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.SagaSchedule
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.Timers

/**
 * Names the commands a context schedules (decision D166): a command of one of the context's [models] by the model,
 * one of another context by the [contracts] (the class's schema in the Manifests of the service and its peers,
 * decision D157), checked to be a command of the target's aggregate type.
 */
class ContextCommandTypes(models: Collection<AggregateModel<*>>, private val contracts: ContractDirectory) :
    CommandTypes {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    override fun nameOf(target: AggregateType, command: Class<*>): String? {
        models[target]?.let { model -> return model.commandTypes.firstOrNull { it.type == command }?.name }
        return contracts.commandTypeOf(Manifest.schemaRef(command.name))?.takeIf { it.startsWith("$target.") }
    }
}

/**
 * Sends a due timer's command for one bounded context (decision D166), as the context's saga sends its derived
 * commands: to an aggregate of the context's [models] through its [coordinator], on this node, which owns the target's
 * partition; to one of another context through the sender, to that context's command topic (decisions D33, D137).
 *
 * The command's id and request id are the timer's id, so a timer sent twice is decided once. Its scope is none, as
 * for any command a system sends; its causation is the commit that scheduled it, and its headers are the scheduling
 * command's: the operator travels in them (decision D163) until the command has a field of its own for it. It is not
 * waited for: a scheduled command is in no caller's wait chain.
 */
class ContextTimerFirer(
    models: Collection<AggregateModel<*>>,
    private val coordinator: WaitCoordinator,
    private val sender: CommandSender? = null,
) : TimerFirer {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val contexts: Set<String> = models.mapTo(HashSet()) { it.type.context }

    override suspend fun fire(timer: Timer): String {
        val wire = CommandWire(
            type = timer.commandType,
            id = timer.id,
            requestId = timer.requestId,
            ref = timer.target,
            body = ClusterCodec.mapper.readTree(timer.body),
            headers = timer.headers,
            causation = timer.causation,
            operator = timer.operator,
        )
        val model = models[timer.target.type] ?: return send(wire)
        val command = checkNotNull(ClusterCodec.command(model, wire)) {
            "${model.type} has no command type ${timer.commandType}, which the timer ${timer.id} sends."
        }
        return when (val outcome = coordinator.forward(model, command, null)) {
            is CommandOutcome.Committed -> TimerOutcomes.COMMITTED
            is CommandOutcome.AlreadyCommitted -> TimerOutcomes.ALREADY_COMMITTED
            is CommandOutcome.Unchanged -> TimerOutcomes.UNCHANGED
            is CommandOutcome.Rejected -> TimerOutcomes.REJECTED.also {
                logger.info {
                    "The timer ${timer.id} sent ${timer.commandType} to ${timer.target}, which rejected it: " +
                        "${outcome.rejection.code} ${outcome.rejection.message}"
                }
            }

            is CommandOutcome.Contended -> throw IllegalStateException(
                "The command of the timer ${timer.id} on ${timer.target} was contended ${outcome.attempts} times.",
            )

            is CommandOutcome.Unknown -> throw IllegalStateException(
                "The outcome of the command of the timer ${timer.id} on ${timer.target} is unknown.",
                outcome.cause,
            )
        }
    }

    private suspend fun send(wire: CommandWire): String {
        val context = wire.ref.type.context
        check(context !in contexts) {
            "Context $context has no aggregate ${wire.ref.type}, which the timer ${wire.id} sends to."
        }
        val sender = checkNotNull(sender) {
            "The timer ${wire.id} sends to ${wire.ref}, of the context $context: a command across contexts goes through " +
                "the command topic (decision D33), and this node has no Kafka transport."
        }
        sender.send(wire)
        return TimerOutcomes.SENT
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

/**
 * Writes the timers a saga of one bounded context schedules (decision D166) into the context's [store], before the
 * saga's checkpoint passes the commit it handled: the subscription calls [write] within its handling, so a failure is
 * retried with it and the timers are written again, idempotently.
 *
 * A saga's timer is kept under its owner `subscription>target` and key ([Timers.sagaOwner]), its id `executionId#n`
 * ([Timers.sagaTimerId]), ordered by the handled commit's time; its partition is the target's among the log's
 * [partitions], so the owner of the target's partition sends it, and its causation is the handled commit. The command
 * is named by [names] and written by [codec] as the kernel writes an aggregate's.
 */
class SagaTimers(
    private val store: TimerStore,
    private val names: CommandTypes,
    private val partitions: Int,
    private val codec: EventCodec = EventCodec(),
) {
    /** Writes [schedules], the saga [subscription]'s schedule changes in its execution of [event]. */
    suspend fun write(subscription: String, event: StateEvent<*>, schedules: List<SagaSchedule>) {
        val version = event.commitTime
        for (schedule in schedules) {
            val owner = Timers.sagaOwner(subscription, schedule.target)
            when (schedule) {
                is SagaSchedule.Put -> store.put(timerOf(owner, version, event, schedule))
                is SagaSchedule.Cancel -> store.cancel(owner, schedule.key, version)
            }
        }
    }

    private fun timerOf(owner: String, version: Long, event: StateEvent<*>, put: SagaSchedule.Put): Timer {
        val type = requireNotNull(names.nameOf(put.target.type, put.command.javaClass)) {
            "A saga schedules ${put.command.javaClass.name} for ${put.target}, a command no Manifest of the service or " +
                "its peers names (decision D139)."
        }
        return Timer(
            id = put.id,
            owner = owner,
            key = put.key,
            version = version,
            partition = Partitioner.partitionOf(put.target, partitions),
            dueAt = put.dueAt,
            target = put.target,
            commandType = type,
            body = codec.commandText(put.command),
            causation = Causation(event.correlationId, event.id),
            headers = event.headers,
            operator = event.operator,
        )
    }
}
