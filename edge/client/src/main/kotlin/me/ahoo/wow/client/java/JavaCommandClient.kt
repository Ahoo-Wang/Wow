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

package me.ahoo.wow.client.java

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * The [CommandClient] for Java callers: the same calls, the in-process client's or
 * a peer's over HTTP alike, without coroutines or Kotlin types. Each call is blocking ([send], [sendAndWait],
 * [stream]) or asynchronous ([sendAsync], [sendAndWaitAsync]); see [JavaFlows] for which thread runs them. Each takes
 * a [CommandRequest], or a command and the id of the aggregate it goes to (none for a creation), its type derived
 * from the command's class.
 *
 * ```java
 * JavaCommandClient commands = JavaCommandClient.of(commandClient).of(caller);
 * CommandResult result = commands.sendAndWait(new SubmitOrder(items));
 * commands.sendAndWait(CommandRequest.builder(new PayOrder(amount)).id(orderId).wait(WaitPlan.COMMITTED).build());
 * ```
 *
 * A rejection is a [me.ahoo.wow.api.client.CommandRejectedException], a technical failure a
 * [me.ahoo.wow.api.client.WowProblemException], both unchecked; an asynchronous call's future completes with them.
 */
@JvmSuppressWildcards
class JavaCommandClient private constructor(private val delegate: CommandClient) {
    /** This client sending as [caller] ([CommandClient.of]). */
    fun of(caller: Caller): JavaCommandClient = JavaCommandClient(delegate.of(caller))

    /** Sends [request] and answers once it is accepted ([CommandClient.send]). */
    fun send(request: CommandRequest): CommandResult = JavaFlows.blocking { delegate.send(request) }

    /** Sends [command] to the aggregate [id] names and answers once it is accepted. */
    @JvmOverloads
    fun send(command: Any, id: String? = null): CommandResult = send(CommandRequest.of(command, id))

    /** [send], asynchronously. */
    fun sendAsync(request: CommandRequest): CompletableFuture<CommandResult> =
        JavaFlows.async { delegate.send(request) }

    /** [send], asynchronously. */
    @JvmOverloads
    fun sendAsync(command: Any, id: String? = null): CompletableFuture<CommandResult> =
        sendAsync(CommandRequest.of(command, id))

    /** Sends [request] and answers with the result its wait plan ends with ([CommandClient.wait]). */
    fun sendAndWait(request: CommandRequest): CommandResult = JavaFlows.blocking { delegate.wait(request) }

    /** Sends [command] to the aggregate [id] names and answers with the result the default wait plan ends with. */
    @JvmOverloads
    fun sendAndWait(command: Any, id: String? = null): CommandResult = sendAndWait(CommandRequest.of(command, id))

    /** [sendAndWait], asynchronously. */
    fun sendAndWaitAsync(request: CommandRequest): CompletableFuture<CommandResult> =
        JavaFlows.async { delegate.wait(request) }

    /** [sendAndWait], asynchronously. */
    @JvmOverloads
    fun sendAndWaitAsync(command: Any, id: String? = null): CompletableFuture<CommandResult> =
        sendAndWaitAsync(CommandRequest.of(command, id))

    /**
     * The results of [request]'s wait, one per hop ([CommandClient.stream]), sent as the stream is made; close
     * it (try-with-resources) to stop reading early.
     */
    fun stream(request: CommandRequest): Stream<CommandResult> = JavaFlows.stream(delegate.stream(request))

    companion object {
        /** The Java facade of [client]. */
        @JvmStatic
        fun of(client: CommandClient): JavaCommandClient = JavaCommandClient(client)
    }
}
