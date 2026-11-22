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

package me.ahoo.wow.cosec

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.testkit.webflux.CoSecTestBindings
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.http.ops.OpsPaths
import me.ahoo.wow.http.ops.parkedRoutes
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI
import java.net.URLEncoder

/**
 * The operator's routes behind CoSec's pipeline, which decides each request as a CoSec gateway does, its bounds on a
 * path among the rest (#6626): an entry of the longest tenant and id the rules allow is read, retried and skipped, since
 * its routes hold no data in their paths. The path that held them, through 10.0.0-beta.16, is one the pipeline refuses
 * before anything is decided.
 */
@OptIn(ExperimentalWowApi::class, ExperimentalCoSecApi::class, InternalWowApi::class)
class ParkedRoutesBehindCoSecTest {
    private val ledger = InMemoryFailureLedger()
    private val failing = Subscription(NAME, SubscriptionFilter(CONTEXT)) { error("${it.ref.id} is broken") }

    /** The node that runs every partition: an action runs here. */
    private val owner = SubscriptionRuntime(
        "group",
        InMemoryEventLog(partitions = 2),
        InMemoryCheckpointStore(),
        ledger,
        listOf(failing),
    )
    private val cosec = CoSecPipeline()
    private val client = WebTestClient
        .bindToRouterFunction(
            parkedRoutes(
                ParkedOps.of(listOf(owner)),
                cartManifest(),
                CoSecCommandAuthorizer(cosec.checker),
                CoSecCallerResolver(CoSecCallerOptions(crossScopeRoles = setOf(OPERATOR))),
            ),
        )
        .webFilter<WebTestClient.RouterFunctionSpec>(CoSecTestBindings.filter(cosec.pipeline))
        .build()

    @AfterEach
    fun stop(): Unit = runBlocking { owner.stop() }

    private fun park(tenant: String, id: String): Unit = runBlocking {
        ledger.park(ParkedAggregate(SubscriptionId(CONTEXT, NAME), CART.ref(tenant, id), 0, 1, "failed", FAILED_AT))
    }

    /** [action] on [id]'s entry of [tenant] as an operator, [members] beside the ones that name it. */
    private fun act(action: String, tenant: String, id: String, members: String = "") = client.post()
        .uri("${OpsPaths.PARKED}/$action")
        .header("Authorization", "Bearer root;(0);$OPERATOR")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """{"subscription":"$CONTEXT.$NAME","context":"$CONTEXT","aggregate":"cart","tenantId":"$tenant",""" +
                """"id":"$id"${if (members.isEmpty()) "" else ",$members"}}""",
        )
        .exchange()
        .expectBody(String::class.java)
        .returnResult()

    @Test
    fun `an entry of the longest tenant and id is read, retried and skipped through CoSec's pipeline`(): Unit =
        runBlocking {
            // A tenant of 256 characters that percent-encode to nine each, and ids of 256 characters.
            val tenant = "租".repeat(256)
            val retried = "r".repeat(256)
            val skipped = "s".repeat(256)
            park(tenant, retried)
            park(tenant, skipped)
            owner.start(setOf(0, 1))

            val read = act(OpsPaths.ENTRY, tenant, retried)
            read.status.value().assert().describedAs(read.responseBody).isEqualTo(200)
            json.readTree(read.responseBody)["aggregate"]["tenantId"].asString().assert().isEqualTo(tenant)
            // Nothing of the cart is in the log: a retry handles nothing, and resolves it.
            val retry = act(OpsPaths.RETRY, tenant, retried)
            retry.status.value().assert().describedAs(retry.responseBody).isEqualTo(200)
            json.readTree(retry.responseBody)["outcome"].asString().assert().isEqualTo("resolved")
            // Run here, or asked of the partition's owner: the entry is skipped, or holds the operator's request.
            val skip = act(OpsPaths.SKIP, tenant, skipped, """"failedAt":$FAILED_AT""")
            skip.status.value().assert().describedAs(skip.responseBody).isIn(200, 202)
            ledger.parked(SubscriptionId(CONTEXT, NAME), CART.ref(tenant, skipped))?.request
                .assert().isIn(null, LedgerRequest.SKIP)
            // Each permitted by the pipeline as a request, then authorized by CoSec as the ledger's system command of
            // the entry's context, on its aggregate.
            cosec.asked.map { it.action }.assert().containsExactly(
                "http:POST",
                "$CONTEXT:${Ledger.READ}",
                "http:POST",
                "$CONTEXT:${Ledger.RETRY_PARKED}",
                "http:POST",
                "$CONTEXT:${Ledger.SKIP_PARKED}",
            )
        }

    @Test
    fun `the path that held the tenant and the id is one CoSec's pipeline refuses before deciding`() {
        val tenant = URLEncoder.encode("租".repeat(256), Charsets.UTF_8)
        val old = "${OpsPaths.PARKED}/$CONTEXT.$NAME/$CONTEXT/cart/$tenant/${"i".repeat(256)}"
        // As received, already encoded: the client encodes no '%' again.
        val refused = client.get().uri(URI.create("http://localhost$old"))
            .header("Authorization", "Bearer root;(0);$OPERATOR")
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        refused.status.value().assert().isEqualTo(400)
        json.readTree(refused.responseBody)["code"].asString().assert().isEqualTo("INVALID_REQUEST")
    }

    private companion object {
        const val NAME = "CartProjector.onAdded"
        const val OPERATOR = "ops.operator"
        const val FAILED_AT = 1_700_000_000_000L
        val CART = AggregateType(CONTEXT, "cart")
    }
}
