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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.ServiceTokens
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CallHeaders
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * A call's wait goes on only when its origin is a node of the calling service (review R2.3-1): a trusted caller must
 * not have the target send its signals, and the credential they carry, to an address of its choosing in the name of
 * the target's own service or of a third one.
 */
class CallWaitOriginTest {
    /** The signals the target sends to other nodes: what an attacker's address would receive. */
    private val sent = CopyOnWriteArrayList<Pair<Origin, WaitSignal>>()
    private val commands = CopyOnWriteArrayList<Command<*>>()
    private val gateway: CommandGateway = run {
        val registry = WaitRegistry()
        val origin = Origin("shop-node-1", "127.0.0.1:9090", TARGET_SERVICE)
        val coordinator = WaitCoordinator(
            Executor(Kernel(InMemoryEventLog(partitions = 2))),
            registry,
            NO_SUBSCRIPTIONS,
            LocalSignalSender(origin.node, registry) { to, signal, _ -> sent += to to signal },
            WaitOptions(origin, maxTimeout = 30.seconds),
        )
        CommandGateway(ModelRegistry(listOf(cartModel(commands))), mapOf(CONTEXT to coordinator))
    }
    private val tokens = ServiceTokens(mapOf(CALLER_SERVICE to listOf(TOKEN)), TENANT)
    private val calls: WebTestClient =
        WebTestClient.bindToRouterFunction(callRoutes(cartManifest(), gateway, tokens.resolver(headerCallers))).build()

    private fun callWith(origin: Origin): Answer {
        val wait = WaitContext("w-1", origin, listOf(Hop.committed(CONTEXT)), System.currentTimeMillis() + 60_000)
        val root = Causation("root-1", "basket/acme/alice@2")
        val facts = mapOf(RequestFacts.APP_ID to "console")
        val call = CallContext("call-1", TENANT, Scope("alice"), root, Operator("alice", TENANT), wait, facts)
        return calls.post().uri("/shop/cart/open-cart")
            .header("Authorization", "Bearer $TOKEN")
            .header(WowHeaders.CALL, CallHeaders.format(call))
            .header("Content-Type", "application/json")
            .bodyValue("""{"note":"hi"}""")
            .answer()
    }

    @Test
    fun `a wait the calling service started should be signalled at its origin`() {
        val origin = Origin("basket-node-1", "10.0.0.7:9191", CALLER_SERVICE)
        callWith(origin).status.assert().isEqualTo(200)
        sent.map { it.first }.assert().contains(origin)
        sent.map { it.second.waitId }.assert().contains("w-1")
    }

    @Test
    fun `a wait naming the target's own service should be dropped, not signalled to the caller's address`() {
        // The exfiltration of R2.3-1: the target would sign this signal with its cluster token.
        callWith(Origin("n", "attacker.example:80", TARGET_SERVICE)).status.assert().isEqualTo(200)
        commands.assert().hasSize(1)
        sent.assert().isEmpty()
        // The call goes on without its wait, still with the root request's facts (decision D236).
        commands.single().headers[RequestFacts.APP_ID].assert().isEqualTo("console")
    }

    @Test
    fun `a wait naming a third service should be dropped too`() {
        // The target would sign this signal with its own service credential.
        callWith(Origin("n", "attacker.example:80", "payment-service")).status.assert().isEqualTo(200)
        commands.assert().hasSize(1)
        sent.assert().isEmpty()
    }

    private companion object {
        const val TARGET_SERVICE = "shop-service"
        const val CALLER_SERVICE = "basket-service"
        const val TOKEN = "basket-service-token-0123456789abcdef"

        val NO_SUBSCRIPTIONS = object : SubscriptionDirectory {
            override fun kindOf(context: String, subscription: String): FunctionKind? = null

            override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false
        }
    }
}
