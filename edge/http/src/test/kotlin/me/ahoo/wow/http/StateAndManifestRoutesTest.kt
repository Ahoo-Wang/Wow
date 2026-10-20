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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.Ownership
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.admission.TenantPolicy
import org.junit.jupiter.api.Test

/** `GET …/{id}/state`, `?version=` (decision D112) and `GET /wow/manifest`. */
class StateAndManifestRoutesTest {
    private val node = HttpNode()
    private val ref = AggregateType(CONTEXT, "cart").ref(TENANT, "c-1")

    /** Two items added by alice, and a snapshot at version 2 as the snapshot subscription would write it. */
    private fun twoItems(snapshot: Boolean = true) {
        node.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        node.post("/shop/cart/c-1/add-item", """{"sku":"pear","quantity":2}""").status.assert().isEqualTo(200)
        if (snapshot) {
            runBlocking {
                val state = Cart().apply { items = 3 }
                node.snapshots.save(EventCodec().snapshotOf(cartModel, ref, state, 2, Scope("alice"), 1L, 2L))
            }
        }
    }

    @Test
    fun `the latest state is the snapshot, its ETag its version and a digest of its body`() {
        twoItems()
        val answer = node.get("/shop/cart/c-1/state")
        answer.status.assert().isEqualTo(200)
        answer.headers.eTag!!.matches(Regex("\"2-[0-9a-f]{16}\"")).assert().isTrue()
        val view = answer.body!!
        view["version"].asInt().assert().isEqualTo(2)
        view["state"]["items"].asInt().assert().isEqualTo(3)
        view["scope"]["ownerId"].asString().assert().isEqualTo("alice")
        view["deleted"].asBoolean().assert().isFalse()
        view["ref"]["id"].asString().assert().isEqualTo("c-1")
    }

    @Test
    fun `If-None-Match with the current tag is 304`() {
        twoItems()
        val tag = node.get("/shop/cart/c-1/state").headers.eTag!!
        node.get("/shop/cart/c-1/state", headers = mapOf("If-None-Match" to tag)).status.assert().isEqualTo(304)
        // The version alone is not the tag (review R4-Q, Q-6).
        node.get("/shop/cart/c-1/state", headers = mapOf("If-None-Match" to "\"2\"")).status.assert().isEqualTo(200)
        node.get("/shop/cart/c-1/state", headers = mapOf("If-None-Match" to "\"1\"")).status.assert().isEqualTo(200)
    }

    @Test
    fun `a state at a version is replayed from the log`() {
        twoItems(snapshot = false)
        val answer = node.get("/shop/cart/c-1/state?version=1")
        answer.status.assert().isEqualTo(200)
        answer.headers.eTag!!.matches(Regex("\"1-[0-9a-f]{16}\"")).assert().isTrue()
        answer.body!!["state"]["items"].asInt().assert().isEqualTo(1)
        node.get("/shop/cart/c-1/state?version=2").body!!["state"]["items"].asInt().assert().isEqualTo(3)
    }

    @Test
    fun `the latest state before the first snapshot is the log replayed (D246)`() {
        twoItems(snapshot = false)
        val answer = node.get("/shop/cart/c-1/state")
        answer.status.assert().isEqualTo(200)
        answer.headers.eTag!!.matches(Regex("\"2-[0-9a-f]{16}\"")).assert().isTrue()
        answer.body!!["version"].asInt().assert().isEqualTo(2)
        answer.body!!["state"]["items"].asInt().assert().isEqualTo(3)
        node.get("/shop/cart/c-1/state", headers = mapOf("If-None-Match" to answer.headers.eTag!!))
            .status.assert().isEqualTo(304)
        // The caller's scope holds as for a snapshot.
        node.get("/shop/cart/c-1/state", user = "bob").assertCode(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `a state that is not there is 404, a version that is not one 400`() {
        twoItems(snapshot = false)
        node.get("/shop/cart/c-2/state").assertCode(404, FrameworkCodes.NOT_FOUND)
        node.get("/shop/cart/c-1/state?version=3").assertCode(404, FrameworkCodes.NOT_FOUND)
        node.get("/shop/cart/c-1/state?version=0").assertCode(400, FrameworkErrors.QUERY_INVALID)
        node.get("/shop/cart/c-1/state?version=one").assertCode(400, FrameworkErrors.QUERY_INVALID)
    }

    @Test
    fun `a state outside the caller's scope is not found (Q-5)`() {
        twoItems()
        node.get("/shop/cart/c-1/state", user = "bob").assertCode(404, FrameworkCodes.NOT_FOUND)
        node.get("/shop/cart/c-1/state?version=1", user = "bob").assertCode(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `a tenant-level caller reads and writes every owner's aggregate of its tenant (D94, D189)`() {
        twoItems()
        val employee = mapOf("X-Tenant-Level" to "true")
        node.get("/shop/cart/c-1/state", user = "erin", headers = employee).status.assert().isEqualTo(200)
        node.post("/shop/cart/c-1/add-item", """{"sku":"plum"}""", user = "erin", headers = employee)
            .status.assert().isEqualTo(200)
        // The owner is still the cart's: the tenant-level caller did not take it over.
        node.get("/shop/cart/c-1/state?version=3", user = "alice").body!!["scope"]["ownerId"].asString()
            .assert().isEqualTo("alice")
        node.get("/shop/cart/c-1/state", user = "bob").assertCode(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `a state of an aggregate of its tenant is read by every owner of the tenant (D189)`() {
        val manifest = cartManifest().let { manifest ->
            val context = manifest.contexts.single()
            manifest.copyWith(
                contexts = listOf(
                    context.copyWith(aggregates = context.aggregates.map { it.copyWith(ownership = Ownership.TENANT) }),
                ),
            )
        }
        val tenantWide = HttpNode(manifest = manifest)
        tenantWide.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        tenantWide.get("/shop/cart/c-1/state?version=1", user = "bob").status.assert().isEqualTo(200)
        tenantWide.get("/shop/cart/c-1/state/trace", user = "bob").status.assert().isEqualTo(200)
    }

    @Test
    fun `Wow-Scope reads in another scope only with the cross-scope permission`() {
        twoItems()
        val scope = mapOf(WowHeaders.SCOPE to """tenant="$TENANT", owner="alice"""")
        node.get("/shop/cart/c-1/state", user = "bob", headers = scope)
            .assertCode(403, FrameworkCodes.AUTH_FORBIDDEN)
        node.get("/shop/cart/c-1/state", user = "admin", headers = scope + ("X-Admin" to "true"))
            .status.assert().isEqualTo(200)
        // Without it, the admin reads in its own scope.
        node.get("/shop/cart/c-1/state", user = "admin", headers = mapOf("X-Admin" to "true"))
            .assertCode(404, FrameworkCodes.NOT_FOUND)
        // Another tenant holds no such cart.
        val otherTenant = mapOf("X-Admin" to "true", WowHeaders.SCOPE to "tenant=other")
        node.get(
            "/shop/cart/c-1/state",
            user = "admin",
            headers = otherTenant
        ).assertCode(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `a type's fixed tenant holds for its commands and its state reads (D116)`() {
        val type = AggregateType(CONTEXT, "cart")
        val fixed = HttpNode(tenants = TenantPolicy.fixed(mapOf(type to "fixed")))
        val added = fixed.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""")
        added.status.assert().isEqualTo(200)
        added.body!!["ref"]["tenantId"].asString().assert().isEqualTo("fixed")

        val state = fixed.get("/shop/cart/c-1/state?version=1")
        state.status.assert().isEqualTo(200)
        state.body!!["ref"]["tenantId"].asString().assert().isEqualTo("fixed")
        // A cross-scope caller may name the fixed tenant, never another one; the others may name none.
        val admin = mapOf("X-Admin" to "true")
        fixed.get(
            "/shop/cart/c-1/state?version=1",
            user = "admin",
            headers = admin + (WowHeaders.SCOPE to "owner=alice")
        )
            .status.assert().isEqualTo(200)
        fixed.get(
            "/shop/cart/c-1/state?version=1",
            user = "admin",
            headers = admin + (WowHeaders.SCOPE to "tenant=fixed")
        )
            .status.assert().isEqualTo(200)
        fixed.get(
            "/shop/cart/c-1/state?version=1",
            user = "admin",
            headers = admin + (WowHeaders.SCOPE to "tenant=other")
        )
            .assertCode(400, FrameworkErrors.QUERY_MALFORMED)
        fixed.get("/shop/cart/c-1/state?version=1", headers = mapOf(WowHeaders.SCOPE to "tenant=fixed"))
            .assertCode(403, FrameworkCodes.AUTH_FORBIDDEN)
        // Without the fixed tenant, the cart would be the caller's: that tenant holds none.
        node.get("/shop/cart/c-1/state?version=1").assertCode(404, FrameworkCodes.NOT_FOUND)
    }

    @Test
    fun `reads are authenticated`() {
        twoItems()
        node.get("/shop/cart/c-1/state", user = null).assertCode(401, FrameworkCodes.UNAUTHENTICATED)
    }

    @Test
    fun `the Manifest is served as its plain tree, authenticated`() {
        val manifest = cartManifest()
        val answer = node.get("/wow/manifest")
        answer.status.assert().isEqualTo(200)
        answer.body.assert().isEqualTo(json.valueToTree(ManifestTree.write(manifest)))
        answer.body!!["contexts"][0]["name"].asString().assert().isEqualTo(CONTEXT)
        node.get("/wow/manifest", user = null).assertCode(401, FrameworkCodes.UNAUTHENTICATED)
    }

    @Test
    fun `the Manifest endpoint can be turned off`() {
        HttpNode(options = WowHttpOptions(manifestEndpoint = false)).get("/wow/manifest").status.assert().isEqualTo(404)
    }

    private fun Answer.assertCode(status: Int, code: String) {
        this.status.assert().isEqualTo(status)
        contentType.assert().isEqualTo("application/problem+json")
        body!!["code"].asString().assert().isEqualTo(code)
        body!!.has("result").assert().isFalse()
    }
}
