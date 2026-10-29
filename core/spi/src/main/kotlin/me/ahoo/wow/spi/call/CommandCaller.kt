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

package me.ahoo.wow.spi.call

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.spi.wait.WaitContext

/**
 * One synchronous call to another context's command gateway: [command], of the full type name [type]
 * (`context.aggregate.Command`), with the [wait] context a wait that follows the calling saga goes on with, or null.
 * The command carries the root command's scope, operator and causation, which the client asserts on the target's
 * internal port, naming its service.
 */
@WowSpi
class OutgoingCall @InternalWowApi constructor(
    val type: String,
    val command: Command<*>,
    val wait: WaitContext? = null
) {
    init {
        require(type.startsWith("${command.ref.type}.")) { "$type is not a command of ${command.ref.type}." }
        require(command.causation != null) { "A call is derived: it carries its causation." }
    }

    /** The command's name within its aggregate type (`SubmitOrder` of `ordering.order.SubmitOrder`). */
    val name: String get() = type.removePrefix("${command.ref.type}.")

    override fun equals(other: Any?): Boolean =
        this === other || other is OutgoingCall && type == other.type && command == other.command && wait == other.wait

    override fun hashCode(): Int = listOf(type, command, wait).hashCode()

    override fun toString(): String = "OutgoingCall(type=$type, command=$command, wait=$wait)"

    companion object {
        /** The call of [command], of the full type name [type], carrying [wait] on. */
        @JvmStatic
        @JvmOverloads
        fun of(type: String, command: Command<*>, wait: WaitContext? = null): OutgoingCall =
            OutgoingCall(type, command, wait)
    }
}

/**
 * The client of the other contexts' HTTP command gateways, which the host gives a node, a port the
 * runtime consumes and `wow-client`'s `HttpCommandCaller` implements: it sends a
 * call to the gateway of its command's context, at the address the host configures for it, and returns the result
 * the target answers, at COMMITTED: SUCCEEDED, REJECTED or FAILED. It throws a [WowProblemException] when it reaches
 * no result (the target down, unreachable or late, no address for the context), retryable with the same request id.
 */
@WowSpi
fun interface CommandCaller {
    suspend fun call(call: OutgoingCall): CommandResult

    /**
     * Whether it knows where to call [context]'s gateway: a node whose sagas call a context its caller
     * does not reach is not built. The default answers true; the host's HTTP caller (`wow-client`'s
     * `HttpCommandCaller`) answers whether its endpoints give [context] an internal URL.
     */
    fun reaches(context: String): Boolean = true
}
