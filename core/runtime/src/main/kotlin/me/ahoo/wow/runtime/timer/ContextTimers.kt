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
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.problem.RetryAdvice
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.ServiceCommandAdmission
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.CommandWire
import me.ahoo.wow.runtime.command.CommandSender
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.busyOf
import me.ahoo.wow.spi.json.BodyAggregateIds
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.observe
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerOutcomes

/**
 * Names the commands a context schedules: a command of one of the context's [models] by the model,
 * one of another context by the [contracts] (the class's schema in the Manifests of the service and its peers),
 * checked to be a command of the target's aggregate type.
 */
internal class ContextCommandTypes(models: Collection<AggregateModel<*>>, private val contracts: ContractDirectory) :
    CommandTypes {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    override fun nameOf(target: AggregateType, command: Class<*>): String? {
        models[target]?.let { model -> return model.commandTypes.firstOrNull { it.type == command }?.name }
        return contracts.commands.commandTypeOf(target.toString(), command)
    }

    /**
     * The aggregate types that decide [command]: the context's models that decide it, and every
     * aggregate of the service's Manifest and the peer contracts that lists its schema.
     */
    override fun aggregatesOf(command: Class<*>): List<AggregateType> {
        val local = models.values.filter { model -> model.commandTypes.any { it.type == command } }.map { it.type }
        return (local + contracts.aggregatesOf(Manifest.schemaRef(command.name))).distinct()
    }

    /**
     * The id of [target]'s aggregate [command] names in its body: as the context's model reads it, or from the property of
     * its JSON a contract names, for another context's.
     */
    override fun aggregateIdOf(target: AggregateType, command: Any): String? {
        models[target]?.let { model -> return model.aggregateIdOf(command) }
        val type = nameOf(target, command.javaClass) ?: return null
        val property = contracts.idPropertyOf(type) ?: return null
        return BodyAggregateIds.of(ClusterCodec.mapper.valueToTree(command), property)
    }
}

/**
 * Sends a due timer's command for one bounded context, as the context's saga sends its derived
 * commands: to an aggregate of the context's [models] through its [coordinator], which routes it to the owner of the
 * target's partition (this node when the target owns the timer, as most do, unless the partition is changing hands; a command
 * another node executed and that changed nothing is reported committed); to one of another context through the sender, to that context's command topic.
 *
 * The command's id and request id are the timer's id and slot (`timer#slot`), so a slot sent twice is decided once;
 * its headers carry the slot ([me.ahoo.wow.api.schedule.ScheduleContext]), which its target reads and
 * whose due time the target's kernel waits for. Its scope is the scheduling commit's, its causation
 * that commit, its operator the scheduling command's, and of the scheduling command's other headers
 * only the request facts that propagate ([RequestFacts.PROPAGATED]), such as the calling application:
 * not where its request came from. It is not waited for: a scheduled command is in no caller's wait chain.
 *
 * A command to an aggregate of its own context is admitted as the context's other commands that no caller sent
 * ([ServiceCommandAdmission], [admission]) before it is forwarded, for the tenant of the aggregate that scheduled it:
 * it goes to its target's type's fixed tenant unless it names another, which refuses it
 * ([me.ahoo.wow.api.result.FrameworkCodes.COMMAND_MALFORMED]); the node's command validators check it, as the gateway
 * checks an admitted one ([me.ahoo.wow.api.result.FrameworkCodes.COMMAND_INVALID]); one to an aggregate whose id is
 * its owner's that names none takes its owner's, and is refused without one; and a creation that names no aggregate
 * takes the id its request created, or a new one: its request id is the slot's, so every firing of one slot creates
 * one aggregate. A refused command is the timer's rejected command, logged with its error, and is never decided. One
 * to another context is checked there, at its intake.
 *
 * Each firing is observed by [observer], its outcome the [TimerOutcomes] it answers: the command goes
 * on in the firing's trace.
 */
internal class ContextTimerFirer(
    models: Collection<AggregateModel<*>>,
    private val coordinator: WaitCoordinator,
    /** The context's admission of the commands no caller sent: its tenants, validators and creation ids. */
    private val admission: ServiceCommandAdmission,
    private val sender: CommandSender? = null,
    private val observer: Observer = Observer.NONE,
) : TimerFirer {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val contexts: Set<String> = models.mapTo(HashSet()) { it.type.context }

    override suspend fun fire(timer: Timer): String {
        val firing = observer.firing(timer)
        return firing.observe { fired(timer).also { firing.outcome(it) } }.value
    }

    private suspend fun fired(timer: Timer): ObservedOutcome {
        val wire = CommandWire(
            type = timer.commandType,
            id = timer.requestId,
            requestId = timer.requestId,
            ref = timer.target,
            body = ClusterCodec.mapper.readTree(timer.body),
            headers = RequestFacts.propagatedOf(timer.headers) + timer.context.headers(),
            scope = timer.scope,
            causation = timer.causation,
            operator = timer.operator,
            // Sent for the aggregate that scheduled it: another context's intake decides its tenant from it.
            actingTenant = timer.owner.tenantId,
        )
        val model = models[timer.target.type] ?: return send(wire)
        val received = checkNotNull(ClusterCodec.command(model, wire)) {
            "${model.type} has no command type ${timer.commandType}, which the timer ${timer.id} sends."
        }
        // The tenant its target names counts unless it is the scheduling aggregate's.
        val acting = timer.owner.tenantId
        admission.refusalOf(model, received, acting)?.let { error ->
            logger.info {
                "The timer ${timer.id} sent ${timer.commandType} to ${timer.target}, which was refused before any " +
                    "aggregate decided it: ${error.code} ${error.message} ${error.violations}"
            }
            return ObservedOutcome.REJECTED
        }
        val command = admission.assign(model, received, acting)
        val settlement = coordinator.forward(model, command, null)
        settlement.rejection?.let { rejection ->
            logger.info {
                "The timer ${timer.id} sent ${timer.commandType} to ${timer.target}, which rejected it: " +
                    "${rejection.code} ${rejection.message}"
            }
            return ObservedOutcome.REJECTED
        }
        // A busy node is backpressure: the runner sends it again without counting an attempt.
        settlement.busyOf(command)?.let { throw it }
        if (settlement.failed) {
            // The result's error is written for a caller: the reason the timer parks with keeps its code (#6588).
            val error = settlement.committed.error
            throw WowProblemException(
                error?.code ?: FrameworkCodes.COMMAND_FAILED,
                FAILED_STATUS,
                RetryAdvice.SAME_REQUEST,
                null,
                "The command of the timer ${timer.id} on ${timer.target} failed: ${error?.code} ${error?.message}",
            )
        }
        return when {
            settlement.committed.duplicate -> ObservedOutcome.DUPLICATE
            settlement.outcome is CommandOutcome.Unchanged -> ObservedOutcome.UNCHANGED
            else -> ObservedOutcome.COMMITTED
        }
    }

    private suspend fun send(wire: CommandWire): ObservedOutcome {
        val context = wire.ref.type.context
        check(context !in contexts) {
            "Context $context has no aggregate ${wire.ref.type}, which the timer ${wire.id} sends to."
        }
        val sender = checkNotNull(sender) {
            "The timer ${wire.id} sends to ${wire.ref}, of the context $context: a command across contexts goes through " +
                "the command topic, and this node has no command transport."
        }
        sender.send(wire)
        return ObservedOutcome.SENT
    }

    private companion object {
        val logger = KotlinLogging.logger {}

        /** The status of the problem a failed command of a timer is: a failure of the service, not the sender's. */
        const val FAILED_STATUS = 500
    }
}
