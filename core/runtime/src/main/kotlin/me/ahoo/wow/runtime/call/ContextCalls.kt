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

import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.CommandCalls
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.call.OutgoingCall
import me.ahoo.wow.spi.wait.WaitContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The synchronous calls of one execution of a saga's handler in [contexts], the contexts of its node:
 * each through [caller], named by [commandTypes], carrying [wait] on. [called] lists the ids of those the target
 * answered (committed or rejected), in order: the saga's hop reports them with the commands it derived, so a wait
 * goes on with them.
 *
 * A call to a context of the node is refused: the saga derives that command instead. The handler's context has already
 * refused a command of a class its `@Sends(calls)` does not declare. Without [caller], a call fails as
 * [CommandCalls.NONE] does, naming the configuration it lacks.
 */
internal class SagaCalls(
    private val caller: CommandCaller?,
    private val contexts: Set<String>,
    private val commandTypes: CommandTypes,
    private val wait: WaitContext?,
) : CommandCalls {
    private val answered: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The calls the targets rejected, by command id: each command and its full type name. */
    private val rejected = ConcurrentHashMap<String, Pair<Command<*>, String>>()

    /** The ids of the calls the targets answered, in order. */
    val called: List<String> get() = synchronized(answered) { answered.toList() }

    /**
     * The receipt of the call whose rejection [rejected] reports, as the saga [execution] made it: what the saga's
     * `@OnRejected` handler of the command takes, as for a command it derived, with the command's class; null when
     * [rejected] is of no call of this execution.
     */
    fun receiptOf(
        rejected: CommandRejectedException,
        execution: ExecutionId
    ): Pair<CommandReceipt.Rejected<Any>, Class<*>>? {
        val result = rejected.result
        val (command, type) = this.rejected[result.commandId] ?: return null
        val receipt = CommandReceipt.Rejected<Any>(
            commandId = command.id,
            commandType = type,
            ref = command.ref,
            error = checkNotNull(result.error) { "A rejected result carries its error." },
            causation = checkNotNull(command.causation) { "A called command carries its causation." },
            executionId = execution,
            operator = command.operator,
            scope = command.scope,
            headers = RequestFacts.propagatedOf(command.headers),
            version = result.version,
        )
        return receipt to command.body.javaClass
    }

    override suspend fun call(command: Command<*>): CommandResult {
        val context = command.ref.type.context
        require(context !in contexts) {
            "A saga calls ${command.ref}, of its own node's context $context: derive the command instead (return it)."
        }
        val client = caller ?: return CommandCalls.NONE.call(command)
        val type = requireNotNull(commandTypes.nameOf(command.ref.type, command.body.javaClass)) {
            "No Manifest of the service or its peers declares the command ${command.body.javaClass.name} called on " +
                "${command.ref}: depend on the api module of $context, whose jar carries its contract."
        }
        val result = client.call(OutgoingCall(type, command, wait))
        if (result.status != CommandResult.Status.FAILED) {
            answered += command.id
        }
        if (result.status == CommandResult.Status.REJECTED) {
            rejected[command.id] = command to type
        }
        return result
    }
}
