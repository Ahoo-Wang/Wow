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

@file:JvmName("CommandClients")

package me.ahoo.wow.api.client

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.decide.requireUse
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.wire.WaitHeader

/**
 * Sends [command] to the aggregate [id] names, with the fields [block] sets, its type derived from its class unless the
 * block sets it: [CommandClient.send] of [command]'s [toCommandRequest].
 */
suspend fun <C : Any> CommandClient.send(
    command: C,
    id: String? = null,
    to: AggregateRef? = null,
    block: CommandRequest.Builder<C>.() -> Unit = {},
): CommandResult = send(command.toCommandRequest(id, to, block))

/**
 * Sends [command] to the aggregate [id] names, with the fields [block] sets (`wait(plan)` among them), and waits for
 * its result: [CommandClient.wait] of [command]'s [toCommandRequest].
 */
suspend fun <C : Any> CommandClient.wait(
    command: C,
    id: String? = null,
    to: AggregateRef? = null,
    block: CommandRequest.Builder<C>.() -> Unit = {},
): CommandResult = wait(command.toCommandRequest(id, to, block))

/**
 * The results of [command] sent to the aggregate [id] names, with the fields [block] sets: [CommandClient.stream] of
 * [command]'s [toCommandRequest].
 */
fun <C : Any> CommandClient.stream(
    command: C,
    id: String? = null,
    to: AggregateRef? = null,
    block: CommandRequest.Builder<C>.() -> Unit = {},
): Flow<CommandResult> = stream(command.toCommandRequest(id, to, block))

/**
 * The aggregate type of [request]: its own, else the one [candidates] (the types that decide its command's class, as
 * the client knows them) holds. A lifecycle command, which every type takes, none or several fail, saying to set the
 * request's type. Wow's clients' rule.
 */
@InternalWowApi
fun typeOf(request: CommandRequest<*>, candidates: (Class<*>) -> List<AggregateType>): AggregateType {
    request.type?.let { return it }
    val command = request.command.javaClass
    requireUse(Lifecycle.nameOf(command) == null) {
        "${command.simpleName} is a lifecycle command every aggregate takes: set CommandRequest.type."
    }
    val types = candidates(command).distinct()
    return types.singleOrNull() ?: throw Misuse.Argument(
        if (types.isEmpty()) {
            "${command.name} is decided by no aggregate the client knows (a context of the service, or a peer whose " +
                "api module is on the class path): set CommandRequest.type."
        } else {
            "${command.name} is decided by several aggregates, ${types.joinToString()}: set CommandRequest.type."
        },
    )
}

/**
 * [request], which [CommandClient.send] sends: it answers on acceptance, so a request that waits for more, a wait plan
 * other than ACCEPTED, fails before anything is sent rather than be answered sooner than it asks. Wow's clients' rule.
 */
@InternalWowApi
fun sendable(request: CommandRequest<*>): CommandRequest<*> = request.also {
    val wait = request.wait ?: return@also
    requireUse(wait.hops.size == 1 && wait.hops.single().stage == Stage.ACCEPTED) {
        "send answers on acceptance: use wait() for the plan ${WaitHeader.format(wait)}."
    }
}

/**
 * The tenant [request] targets, as [callerTenant] sends it: its target tenant, unless it is the caller's own, which
 * changes nothing. Wow's clients' rule.
 */
@InternalWowApi
fun targetTenantOf(request: CommandRequest<*>, callerTenant: String): String? =
    request.targetTenant?.takeUnless { it == callerTenant }
