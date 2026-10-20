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

package me.ahoo.wow.runtime.call

import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.kernel.CommandCalls
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.spi.wait.WaitContext
import java.util.Collections

/**
 * One synchronous call to another context's command gateway (decision D193): [command], of the full type name [type]
 * (`context.aggregate.Command`), with the [wait] context a wait that follows the calling saga goes on with, or null.
 * The command carries the root command's scope, operator and causation, which the client asserts with the service's
 * credential.
 */
data class OutgoingCall(val type: String, val command: Command<*>, val wait: WaitContext? = null) {
    init {
        require(type.startsWith("${command.ref.type}.")) { "$type is not a command of ${command.ref.type}." }
        require(command.causation != null) { "A call is derived: it carries its causation." }
    }

    /** The command's name within its aggregate type (`SubmitOrder` of `ordering.order.SubmitOrder`). */
    val name: String get() = type.removePrefix("${command.ref.type}.")
}

/**
 * The client of the other contexts' HTTP command gateways (decision D193), which the host gives a node: it sends a
 * call to the gateway of its command's context, at the address the host configures for it, and returns the result
 * the target answers, at COMMITTED: SUCCEEDED, REJECTED or FAILED. It throws a [CallFailedException] when it reaches
 * no result (the target down, unreachable or late, a credential it refuses, no address for the context).
 */
fun interface CommandCaller {
    suspend fun call(call: OutgoingCall): CommandResult

    /**
     * Whether it knows where to call [context]'s gateway (decision D259): a node whose sagas call a context its caller
     * does not reach is not built. The default answers true; the host's HTTP caller (`wow-client`'s
     * `HttpCommandCaller`) answers whether its endpoints give [context] an internal URL.
     */
    fun reaches(context: String): Boolean = true
}

/**
 * The synchronous calls of one execution of a saga's handler in [contexts], the contexts of its node (decision D193):
 * each through [caller], named by [commandTypes], carrying [wait] on. [called] lists the ids of those the target
 * answered (committed or rejected), in order: the saga's hop reports them with the commands it derived, so a wait
 * goes on with them.
 *
 * A call to a context of the node is refused: the saga derives that command instead. So is a command of a class the
 * handler does not [declare] in its `@OnEvent(calls)` (decision D259), which the Manifest would not list. Without
 * [caller], a call fails
 * as [CommandCalls.NONE] does, naming the configuration it lacks. A command built without a causation is its own root,
 * as one a saga derives so ([me.ahoo.wow.runtime.binding.DerivedCommands]).
 */
internal class SagaCalls(
    private val caller: CommandCaller?,
    private val contexts: Set<String>,
    private val commandTypes: CommandTypes,
    private val wait: WaitContext?,
    /** The command classes the handler declares it calls (`@OnEvent(calls)`): the only ones it may call. */
    private val declare: List<Class<*>> = emptyList(),
    /** The handler's subscription, `processor.function`, as a refusal names it. */
    private val subscription: String = "The saga",
) : CommandCalls {
    private val answered: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The ids of the calls the targets answered, in order. */
    val called: List<String> get() = synchronized(answered) { answered.toList() }

    override suspend fun call(command: Command<*>): CommandResult {
        val context = command.ref.type.context
        require(context !in contexts) {
            "A saga calls ${command.ref}, of its own node's context $context: derive the command instead (return it)."
        }
        val body = command.body.javaClass
        require(body in declare) {
            "$subscription calls ${body.name}, which its @OnEvent(calls) does not declare: add ${body.simpleName}::class " +
                "to it, so the Manifest lists the call (decision D259)."
        }
        val client = caller ?: return CommandCalls.NONE.call(command)
        val type = requireNotNull(commandTypes.nameOf(command.ref.type, command.body.javaClass)) {
            "No Manifest of the service or its peers declares the command ${command.body.javaClass.name} called on " +
                "${command.ref}: depend on the api module of $context, whose jar carries its contract (decision D234)."
        }
        val derived = command.causation?.let { command } ?: command.copyWith(causation = Causation(command.id, command.id))
        val result = client.call(OutgoingCall(type, derived, wait))
        if (result.status != CommandResult.Status.FAILED) {
            answered += derived.id
        }
        return result
    }
}
