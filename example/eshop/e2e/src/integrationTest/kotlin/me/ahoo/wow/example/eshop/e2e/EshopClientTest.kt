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

package me.ahoo.wow.example.eshop.e2e

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.client.ForwardedHeaders
import me.ahoo.wow.example.eshop.basket.server.BasketServer
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.ordering.api.order.OrderItem
import me.ahoo.wow.example.eshop.ordering.api.order.SubmitOrder
import me.ahoo.wow.example.eshop.ordering.server.OrderingServer
import me.ahoo.wow.spring.boot.InternalServer
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * The typed clients of a peer (decisions D257, D259): the basket service calls and reads the ordering service through
 * `wow-client`, by the ordering's generated contract (`EshopOrderingContract`), with no path written. Its
 * [CommandClient] and [QueryApi] beans route the ordering context, which `wow.clients.contexts` lists, over HTTP:
 *
 * - **Service mode**: on the ordering's internal port, as the basket service (its token), asserting the buyer as the
 *   root context: the order is the buyer's, and the buyer's reads see it;
 * - **User mode**: on the ordering's public routes, forwarding the buyer's own credential (`X-User` here), which the
 *   ordering authenticates as its edge does.
 *
 * The services run in this JVM on the memory backend, as [EshopSyncCallTest]'s do.
 */
class EshopClientTest {
    private val alice = Caller.of(scope = Scope("alice"), subject = "alice")
    private val items = listOf(OrderItem("mug", BigDecimal("8.50"), 2))

    @Test
    fun `the basket should submit and read the buyer's order on the ordering's internal port`(): Unit = runBlocking {
        start(OrderingServer::class.java, "ordering").use { ordering ->
            val basket = start(
                BasketServer::class.java,
                "basket",
                "--wow.clients.contexts.eshop-ordering.internal-url=http://127.0.0.1:${ordering.internalPort}",
            )
            basket.use {
                // region send
                val ref = EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-1")
                val submitted = basket.bean<CommandClient>().of(alice).wait(SubmitOrder(items), ref)
                submitted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                submitted.ref.assert().isEqualTo(ref)
                // endregion

                // The same request id again is the same order (decision D191).
                // region read
                val queries = basket.bean<QueryApi>().of(alice).snapshots(EshopOrderingContract.Order.TYPE)
                val state = queries.state("client-alice-1")!!
                state.scope.ownerId.assert().isEqualTo("alice")
                (state.state as ObjectNode)["items"].size().assert().isEqualTo(1)
                // On the memory backend the ordering serves no query model: a list is its 404, never a command.
                val unserved = runCatching { queries.list(ListQuery(MatchAllFilter)).toList() }.exceptionOrNull()
                (unserved as WowProblemException).status.assert().isEqualTo(NOT_FOUND)
                // Bob, another buyer, sees none of it.
                val bob = Caller.of(scope = Scope("bob"), subject = "bob")
                basket.bean<QueryApi>().of(bob).snapshots(EshopOrderingContract.Order.TYPE).state("client-alice-1")
                    .assert().isNull()
                // endregion

                // region rejected
                // An order of no items is the ordering's rejection, typed.
                val rejected = runCatching {
                    basket.bean<CommandClient>().of(alice)
                        .wait(SubmitOrder(emptyList()), EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-2"))
                }.exceptionOrNull() as CommandRejectedException
                rejected.code.assert().contains("invalid")
                // endregion
            }
        }
    }

    @Test
    fun `the basket should call the ordering's public routes with the buyer's forwarded credential`(): Unit =
        runBlocking {
            start(OrderingServer::class.java, "ordering").use { ordering ->
                val basket = start(
                    BasketServer::class.java,
                    "basket",
                    "--wow.clients.contexts.eshop-ordering.url=http://127.0.0.1:${ordering.port}",
                    // The checkout saga's synchronous call, which the node checks it reaches (decision D259).
                    "--wow.clients.contexts.eshop-ordering.internal-url=http://127.0.0.1:${ordering.internalPort}",
                    "--wow.clients.contexts.eshop-ordering.mode=user",
                    "--wow.clients.forwarded-headers=X-User",
                )
                basket.use {
                    val ref = EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-bob-1")
                    withContext(ForwardedHeaders(mapOf("X-User" to "bob"))) {
                        val results = basket.bean<CommandClient>().stream(SubmitOrder(items), ref).toList()
                        results.last().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                        val state = basket.bean<QueryApi>().of(Caller.ANONYMOUS)
                            .snapshots(EshopOrderingContract.Order.TYPE).state("client-bob-1")!!
                        state.scope.ownerId.assert().isEqualTo("bob")
                    }
                    // With another buyer's credential, the ordering does not show the buyer's order.
                    withContext(ForwardedHeaders(mapOf("X-User" to "alice"))) {
                        basket.bean<QueryApi>().of(Caller.ANONYMOUS)
                            .snapshots(EshopOrderingContract.Order.TYPE).state("client-bob-1").assert().isNull()
                    }
                }
            }
        }

    private class Service(val context: ConfigurableApplicationContext) : AutoCloseable {
        val port: Int = context.environment.getRequiredProperty("local.server.port", Int::class.java)
        val internalPort: Int = context.getBean(InternalServer::class.java).boundPort

        inline fun <reified T : Any> bean(): T = context.getBean(T::class.java)

        override fun close() {
            context.close()
        }
    }

    private companion object {
        const val NOT_FOUND = 404

        fun start(application: Class<*>, context: String, vararg arguments: String): Service = Service(
            SpringApplicationBuilder(application, InMemoryTopicsBeans::class.java).run(
                *arguments,
                "--spring.main.banner-mode=off",
                "--spring.application.name=eshop-$context-service",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--wow.service=eshop-$context-service",
                "--wow.backend=memory",
                "--wow.internal.host=127.0.0.1",
                "--wow.internal.port=0",
                "--wow.contexts.include=eshop-$context",
                *EshopTokens.arguments(context).toTypedArray(),
            ),
        )
    }
}
