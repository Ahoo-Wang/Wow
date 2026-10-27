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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * The [CommandClient] for Java callers: the same calls, the in-process client's or
 * a peer's over HTTP alike, without coroutines or Kotlin types. Each call is blocking ([send], [sendAndWait],
 * [stream]) or asynchronous ([sendAsync], [sendAndWaitAsync]); see [JavaFlows] for which thread runs them.
 *
 * ```java
 * JavaCommandClient commands = JavaCommandClient.of(commandClient).of(caller);
 * CommandResult result = commands.sendAndWait(new SubmitOrder(items), OrderingContract.Order.ref(tenant, id));
 * ```
 *
 * A rejection is a [me.ahoo.wow.api.client.CommandRejectedException], a technical failure a
 * [me.ahoo.wow.api.client.WowProblemException], both unchecked; an asynchronous call's future completes with them.
 */
@JvmSuppressWildcards
class JavaCommandClient private constructor(private val delegate: CommandClient) {
    /** This client sending as [caller] ([CommandClient.of]). */
    fun of(caller: Caller): JavaCommandClient = JavaCommandClient(delegate.of(caller))

    /** Sends [command] to [ref] and answers once it is accepted ([CommandClient.send]). */
    @JvmOverloads
    fun send(command: Any, ref: AggregateRef, options: SendOptions = SendOptions.DEFAULT): CommandResult =
        JavaFlows.blocking { delegate.send(command, ref, options) }

    /** [send], asynchronously. */
    @JvmOverloads
    fun sendAsync(
        command: Any,
        ref: AggregateRef,
        options: SendOptions = SendOptions.DEFAULT,
    ): CompletableFuture<CommandResult> = JavaFlows.async { delegate.send(command, ref, options) }

    /** Sends [command] to [ref] and answers with the result its wait plan ends with ([CommandClient.wait]). */
    @JvmOverloads
    fun sendAndWait(command: Any, ref: AggregateRef, options: SendOptions = SendOptions.DEFAULT): CommandResult =
        JavaFlows.blocking { delegate.wait(command, ref, options) }

    /** [sendAndWait], asynchronously. */
    @JvmOverloads
    fun sendAndWaitAsync(
        command: Any,
        ref: AggregateRef,
        options: SendOptions = SendOptions.DEFAULT,
    ): CompletableFuture<CommandResult> = JavaFlows.async { delegate.wait(command, ref, options) }

    /**
     * The results of [command]'s wait, one per hop ([CommandClient.stream]), sent as the stream is made; close
     * it (try-with-resources) to stop reading early.
     */
    @JvmOverloads
    fun stream(command: Any, ref: AggregateRef, options: SendOptions = SendOptions.DEFAULT): Stream<CommandResult> =
        JavaFlows.stream(delegate.stream(command, ref, options))

    companion object {
        /** The Java facade of [client]. */
        @JvmStatic
        fun of(client: CommandClient): JavaCommandClient = JavaCommandClient(client)

        /** A builder of [SendOptions] with Java types: the wait's timeout a [java.time.Duration]. */
        @JvmStatic
        fun options(): Options = Options()
    }

    /** Builds [SendOptions]; each setting left out is as in [SendOptions.DEFAULT]. */
    class Options internal constructor() {
        private var wait: WaitPlan? = null
        private var timeout: JavaDuration? = null
        private var requestId: String? = null
        private var expectedVersion: Int? = null
        private var targetTenant: String? = null
        private var targetScope: Scope? = null

        fun wait(wait: WaitPlan): Options = apply { this.wait = wait }

        fun timeout(timeout: JavaDuration): Options = apply { this.timeout = timeout }

        fun requestId(requestId: String): Options = apply { this.requestId = requestId }

        fun expectedVersion(expectedVersion: Int): Options = apply { this.expectedVersion = expectedVersion }

        fun targetTenant(targetTenant: String): Options = apply { this.targetTenant = targetTenant }

        fun targetScope(targetScope: Scope): Options = apply { this.targetScope = targetScope }

        fun build(): SendOptions = SendOptions.of(
            wait = wait,
            timeout = timeout?.toKotlinDuration(),
            requestId = requestId,
            expectedVersion = expectedVersion,
            targetTenant = targetTenant,
            targetScope = targetScope,
        )
    }
}
