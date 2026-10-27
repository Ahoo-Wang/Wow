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
import me.ahoo.wow.api.decide.Command
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
                    "takes it, so write its full ref, context.command(ref, body)."

            targets.isEmpty() ->
                "$handler sends ${command.name} without its target, and no contract assigns it an aggregate: " +
                    "neither the service's Manifest nor a peer contract on the class path " +
                    "(META-INF/wow/contract.json of an api jar) names it. Add the api module of the bounded context " +
                    "that decides it to the dependencies, or write its full ref, context.command(ref, body)."

            else ->
                "$handler sends ${command.name} without its target, but several aggregate types decide it: " +
                    "${targets.joinToString()}. Write its full ref, context.command(ref, body), naming one of them."
        },
    )
}

/**
 * The context an `@OnSchedule` job's handler gets for [tick]: [context], the saga context of the execution of the
 * job instance's commit, whose commands are the job's: one of an id or a body goes to the aggregate type its class's
 * contract names, in the instance's tenant ([ScheduleTick.tenantId]).
 */
internal class ExecutionJobContext(private val context: SagaContext, override val tick: ScheduleTick) : JobContext {
    override val executionId: ExecutionId get() = context.executionId
    override val invocationKey: String get() = context.invocationKey
    override val queries: SubscriptionQueries get() = context.queries
    override val ids: Ids get() = context.ids

    override fun <C : Any> command(ref: AggregateRef, body: C): Command<C> = context.command(ref, body)

    override fun <C : Any> command(id: String, body: C): Command<C> = context.command(id, body)

    override fun <C : Any> command(body: C): Command<C> = context.command(body)

    override fun toString(): String = "ExecutionJobContext(executionId=$executionId, tick=$tick)"
}
