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
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import me.ahoo.wow.api.lifecycle.Lifecycle as Lifecycles
import me.ahoo.wow.manifest.AggregateIdSource as ManifestIdSource
import me.ahoo.wow.manifest.Creation as ManifestCreation

/**
 * The routes of an aggregate whose id is its owner's: each command also without an id, the caller's own, and its
 * state read by no id too.
 */
class OwnerIdRoutesTest {
    data class OpenBasket(val note: String = "")
    data class PutItem(val sku: String)
    data class BasketOpened(val note: String)
    data class ItemPut(val sku: String)

    class Basket {
        var items = 0
    }

    private val basketModel = aggregate<Basket>(CONTEXT, "basket") {
        id(AggregateIdSource.OWNER)
        initial { Basket() }
        decide<OpenBasket>(Creation.REQUIRED) { _, body -> Decision.events(BasketOpened(body.note)) }
        decide<PutItem> { _, body -> Decision.events(ItemPut(body.sku)) }
        evolve<BasketOpened> { }
        evolve<ItemPut> { items++ }
    }

    /** The cart's Manifest with the basket as the compiler writes an aggregate whose id is its owner's. */
    private val manifest: Manifest = cartManifest().let { manifest ->
        fun command(name: String, creation: ManifestCreation) = CommandManifest(
            name = name,
            schema = Manifest.schemaRef(name),
            creation = creation,
            routes = CommandRoutes.of(CONTEXT, "basket", name, creation, unaddressed = true),
        )
        val basket = AggregateManifest(
            name = "basket",
            state = StateManifest(Manifest.schemaRef("Basket")),
            commands = listOf(command("OpenBasket", ManifestCreation.REQUIRED), command("PutItem", ManifestCreation.NONE)) +
                Lifecycles.COMMANDS.filterValues { it != TransferOwner::class.java }.keys.sorted().map {
                    SystemCommands.lifecycle(CONTEXT, "basket", it, owned = true)
                },
            idSource = ManifestIdSource.OWNER,
        )
        val context = manifest.contexts.single()
        manifest.copyWith(contexts = listOf(context.copyWith(aggregates = context.aggregates + basket)))
    }

    /** The test headers' callers, and a caller of the tenant with no credential without `X-User`. */
    private val callers = CallerResolver { request -> headerCallers.resolve(request) ?: Caller(TENANT) }

    private val node = HttpNode(manifest = manifest, callers = callers, aggregates = listOf(basketModel))

    private val tenantLevel = mapOf("X-Tenant-Level" to "true")

    private fun Answer.assertSucceeded(): Answer {
        status.assert().describedAs(body.toString()).isEqualTo(200)
        body!!["status"].asString().assert().isEqualTo("succeeded")
        return this
    }

    private fun Answer.assertCode(status: Int, code: String): Answer {
        this.status.assert().describedAs(body.toString()).isEqualTo(status)
        contentType.assert().isEqualTo("application/problem+json")
        body!!["code"].asString().assert().isEqualTo(code)
        return this
    }

    @Test
    fun `an owner-level caller creates its own by no id, then addresses it by its id, and no other`() {
        node.post("/shop/basket/open-basket").assertSucceeded().body!!["ref"]["id"].asString().assert()
            .isEqualTo("alice")
        node.post("/shop/basket/put-item", """{"sku":"apple"}""").assertSucceeded().body!!["version"].asInt()
            .assert().isEqualTo(2)
        node.post("/shop/basket/alice/put-item", """{"sku":"pear"}""").assertSucceeded()
        node.post("/shop/basket/bob/put-item", """{"sku":"pear"}""")
            .assertCode(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
        node.post("/shop/basket/bob/open-basket").assertCode(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
        node.get("/shop/basket/alice/state").body!!["state"]["items"].asInt().assert().isEqualTo(2)
    }

    @Test
    fun `the delete and the other lifecycle commands by no id are the caller's own`() {
        node.post("/shop/basket/open-basket").assertSucceeded()
        node.client.delete().uri("/shop/basket").header("X-User", "alice").answer().assertSucceeded()
            .body!!["ref"]["id"].asString().assert().isEqualTo("alice")
        node.post("/shop/basket/put-item", """{"sku":"apple"}""").assertCode(410, FrameworkCodes.AGGREGATE_DELETED)
        node.post("/shop/basket/wow.lifecycle.restore-aggregate", body = null).assertSucceeded()
        node.post("/shop/basket/put-item", """{"sku":"apple"}""").assertSucceeded()
        // Another owner deletes only its own, which it does not have.
        node.client.delete().uri("/shop/basket").header("X-User", "bob").answer()
            .assertCode(404, FrameworkCodes.AGGREGATE_NOT_FOUND)
        node.client.delete().uri("/shop/basket/alice").header("X-User", "bob").answer()
            .assertCode(403, FrameworkCodes.AGGREGATE_FORBIDDEN)
    }

    @Test
    fun `the state and its trace by no id are the caller's own`() {
        node.post("/shop/basket/open-basket").assertSucceeded()
        node.post("/shop/basket/put-item", """{"sku":"apple"}""").assertSucceeded()
        val state = node.get("/shop/basket/state")
        state.status.assert().describedAs(state.body.toString()).isEqualTo(200)
        state.body!!["aggregateId"].asString().assert().isEqualTo("alice")
        state.body!!["state"]["items"].asInt().assert().isEqualTo(1)
        state.body!!["ownerId"].asString().assert().isEqualTo("alice")
        val trace = node.get("/shop/basket/state/trace")
        trace.status.assert().describedAs(trace.body.toString()).isEqualTo(200)
        trace.body!!["ref"]["id"].asString().assert().isEqualTo("alice")
        trace.body!!["commits"].size().assert().isEqualTo(2)
        // Bob has none of his own.
        node.get("/shop/basket/state", user = "bob").assertCode(404, FrameworkCodes.AGGREGATE_NOT_FOUND)
        // An administrator acting in alice's scope reads hers.
        node.get(
            "/shop/basket/state",
            user = "admin",
            headers = mapOf("X-Admin" to "true", WowHeaders.SCOPE to """tenant="$TENANT", owner="alice""""),
        ).body!!["aggregateId"].asString().assert().isEqualTo("alice")
    }

    @Test
    fun `a caller with no owner names the id, and without one is malformed, or unauthenticated`() {
        node.post("/shop/basket/open-basket", user = "clerk", headers = tenantLevel)
            .assertCode(400, FrameworkCodes.COMMAND_MALFORMED)
            .body!!["detail"].asString().assert().isEqualTo(
            "This caller has no owner; address the aggregate by its id."
        )
        node.post("/shop/basket/open-basket", user = null).assertCode(401, FrameworkCodes.AUTH_UNAUTHENTICATED)
        node.get("/shop/basket/state", user = "clerk", headers = tenantLevel)
            .assertCode(400, FrameworkCodes.QUERY_MALFORMED)
        node.get("/shop/basket/state/trace", user = "clerk", headers = tenantLevel)
            .assertCode(400, FrameworkCodes.QUERY_MALFORMED)
        node.get("/shop/basket/state", user = null).assertCode(401, FrameworkCodes.AUTH_UNAUTHENTICATED)
        node.get("/shop/basket/state/trace", user = null).assertCode(401, FrameworkCodes.AUTH_UNAUTHENTICATED)
        // By its id, the tenant-level caller acts on and reads the owner's aggregate.
        node.post("/shop/basket/carol/open-basket", user = "clerk", headers = tenantLevel).assertSucceeded()
        node.get("/shop/basket/carol/state", user = "clerk", headers = tenantLevel).body!!["ownerId"]
            .asString().assert().isEqualTo("carol")
        node.get("/shop/basket/state", user = "carol").body!!["aggregateId"].asString().assert().isEqualTo("carol")
    }

    @Test
    fun `an aggregate of assigned ids has no state route by no id`() {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").assertSucceeded()
        node.get("/shop/cart/state").status.assert().isEqualTo(404)
        node.get("/shop/cart/state/trace").status.assert().isEqualTo(404)
        node.get("/shop/cart/c-1/state").status.assert().isEqualTo(200)
    }
}
