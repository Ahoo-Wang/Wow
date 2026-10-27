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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.JobContext
import me.ahoo.wow.api.subscribe.SagaContext

/**
 * The aggregate type a command of the class [command] goes to when the saga handler [handler] leaves its ref out:
 * the one type [CommandTypes.aggregatesOf] gives. Fails with an [IllegalArgumentException] that
 * says what to write instead when there is none (a lifecycle command, which every type takes, or a class no contract
 * on the class path assigns: its context's api module is missing) or several (it names them).
 */
internal fun CommandTypes.omittedTargetOf(command: Class<*>, handler: String): AggregateType {
    val targets = aggregatesOf(command).distinct()
    return targets.singleOrNull() ?: throw IllegalArgumentException(
        when {
            Lifecycle.nameOf(command) != null ->
                "$handler sends the lifecycle command ${command.name} without its target: every aggregate type " +
                    "takes it, so give the request its type, CommandRequest.to(ref, body)."

            targets.isEmpty() ->
                "$handler sends ${command.name} without its target, and no contract assigns it an aggregate: " +
                    "neither the service's Manifest nor a peer contract on the class path " +
                    "(META-INF/wow/contract.json of an api jar) names it. Add the api module of the bounded context " +
                    "that decides it to the dependencies, or give the request its type, CommandRequest.to(ref, body)."

            else ->
                "$handler sends ${command.name} without its target, but several aggregate types decide it: " +
                    "${targets.joinToString()}. Give the request one of them as its type, CommandRequest.to(ref, body)."
        },
    )
}

/**
 * The aggregate this request derived by the handler [handler] goes to, as
 * [me.ahoo.wow.api.subscribe.DerivingContext] describes: its type, else the one [commandTypes] assigns the command's
 * class ([omittedTargetOf]); its target tenant, else [tenantId], the execution's; its id, else none (a creation the
 * target's admission gives one). A request with a wait or a timeout is refused: the subscription waits for none.
 */
internal fun CommandRequest<*>.derivedTarget(
    handler: String,
    tenantId: String,
    commandTypes: CommandTypes
): AggregateRef {
    require(wait == null) { refusal(handler, "a wait (CommandRequest.wait)") }
    require(timeout == null) { refusal(handler, "a timeout (CommandRequest.timeout)") }
    val type = type ?: commandTypes.omittedTargetOf(command.javaClass, handler)
    val tenant = targetTenant ?: tenantId
    return id?.let { type.ref(tenant, it) } ?: type.unassigned(tenant)
}

private fun CommandRequest<*>.refusal(handler: String, field: String): String =
    "$handler derives ${command.javaClass.name} with $field: the subscription sends the commands a handler derives " +
        "and waits for none, so a derived request takes neither a wait nor a timeout."

/**
 * The context an `@OnSchedule` job's handler gets for [tick]: [context], the saga context of the execution of the
 * job instance's commit, whose derivation turns the requests the job returns into its commands: one without a
 * target tenant goes to the instance's tenant ([ScheduleTick.tenantId]).
 */
internal class ExecutionJobContext(private val context: SagaContext, override val tick: ScheduleTick) : JobContext {
    override val executionId: ExecutionId get() = context.executionId
    override val invocationKey: String get() = context.invocationKey
    override val queries: SubscriptionQueries get() = context.queries
    override val ids: Ids get() = context.ids

    override fun toString(): String = "ExecutionJobContext(executionId=$executionId, tick=$tick)"
}
