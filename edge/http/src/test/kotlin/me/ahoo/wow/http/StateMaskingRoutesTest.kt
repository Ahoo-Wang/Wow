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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

/**
 * The state reads and the `events` model mask what the snapshot queries mask (09 §17 G-2, the 9.x
 * `PointReadAdmission`): the cart's `note` is sensitive in its state schema (`DISPLAY`, two code points kept) and in
 * its `CartOpened` event (`CONFIDENTIAL`, all masked). As a snapshot query, a read masks for every caller alike; a
 * caller the query policies hide the cart from does not read it at all.
 */
class StateMaskingRoutesTest {
    private val hideFromBob =
        QueryPolicy { caller, _ -> if (caller.subject == "bob") MatchNoneFilter else MatchAllFilter }
    private val tenantLevel = mapOf("X-Tenant-Level" to "true")

    /** A cart opened with [SECRET] as its note by alice, its snapshot stored where the state and the queries read it. */
    private fun opened(node: QueryNode): QueryNode {
        node.http.post("/shop/cart/c-1/open-cart", """{"note":"$SECRET"}""").status.assert().isEqualTo(200)
        node.http.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        runBlocking {
            val state = Cart().apply {
                items = 1
                note = SECRET
            }
            node.http.snapshots.save(
                EventCodec().snapshotOf(cartModel, cartType.ref(TENANT, "c-1"), state, 2, Scope("alice"), 1L, 2L)
            )
        }
        node.storage.save(
            SnapshotRecord(
                ref = cartType.ref(TENANT, "c-1"),
                version = 2,
                schemaFingerprint = "fp",
                state = """{"items":1,"note":"$SECRET"}""",
                scope = Scope("alice"),
                deleted = false,
                firstEventTime = 1L,
                lastEventTime = 2L,
            ),
        )
        return node
    }

    private fun QueryNode.read(path: String, user: String, headers: Map<String, String> = emptyMap()): Answer =
        http.get(path, user, tenantLevel + headers)

    @Test
    fun `a state by id, version and time is masked as the snapshot query masks it, for every caller`() {
        val node = opened(QueryNode(manifest = sensitiveManifest(), policies = listOf(hideFromBob)))
        val queried = node.query("""{"kind":"single","filter":{"op":"id","value":"c-1"}}""").body!!
        queried["state"]["note"].asString().assert().isEqualTo(DISPLAYED)
        listOf("alice", "admin").forEach { user ->
            listOf(
                "/shop/cart/c-1/state",
                "/shop/cart/c-1/state?version=1",
                "/shop/cart/c-1/state?at=2100-01-01T00:00:00Z",
            ).forEach { path ->
                val answer = node.read(path, user)
                answer.status.assert().describedAs("$path as $user").isEqualTo(200)
                answer.body!!["state"]["note"].asString().assert().describedAs("$path as $user").isEqualTo(DISPLAYED)
            }
            node.read("/shop/cart/c-1/state", user).body!!["state"].assert().isEqualTo(queried["state"])
        }
        // A caller the policies hide the cart from reads none of it.
        node.read("/shop/cart/c-1/state", "bob").status.assert().isEqualTo(404)
        node.read("/shop/cart/c-1/state?version=1", "bob").status.assert().isEqualTo(404)
    }

    @Test
    fun `a trace masks each commit's state and each of its events`() {
        val node = opened(QueryNode(manifest = sensitiveManifest(), policies = listOf(hideFromBob)))
        val commits = node.read("/shop/cart/c-1/state/trace", "alice").body!!["commits"]
        commits.size().assert().isEqualTo(2)
        commits.forEach { it["state"]["note"].asString().assert().isEqualTo(DISPLAYED) }
        commits[0]["events"][0]["name"].asString().assert().isEqualTo("shop.cart.CartOpened")
        commits[0]["events"][0]["body"]["note"].asString().assert().isEqualTo(CONFIDENTIAL)
        // A field the event's schema does not protect is left as it is.
        commits[1]["events"][0]["body"]["sku"].asString().assert().isEqualTo("apple")
        node.read("/shop/cart/c-1/state/trace", "bob").status.assert().isEqualTo(404)
    }

    @Test
    fun `the events model masks each event body by its schema`() {
        val node = opened(QueryNode(manifest = sensitiveManifest(), policies = listOf(hideFromBob)))
        val streams = node.query(
            """{"kind":"list","filter":{"op":"eq","field":"aggregateId","value":"c-1"}}""",
            "alice",
            QueryRoutes.EVENTS,
            tenantLevel,
        ).body!!
        streams.size().assert().isEqualTo(2)
        streams[0]["events"][0]["body"]["note"].asString().assert().isEqualTo(CONFIDENTIAL)
        streams[1]["events"][0]["body"]["sku"].asString().assert().isEqualTo("apple")
    }

    @Test
    fun `the ETag is the same for every caller since the masks are, and 304 still answers it`() {
        val node = opened(QueryNode(manifest = sensitiveManifest()))
        val alice = node.read("/shop/cart/c-1/state", "alice")
        val admin = node.read("/shop/cart/c-1/state", "admin")
        alice.headers.eTag!!.matches(Regex("\"2-[0-9a-f]{16}\"")).assert().isTrue()
        admin.headers.eTag.assert().isEqualTo(alice.headers.eTag)
        admin.body.assert().isEqualTo(alice.body)
        val revalidated = node.read("/shop/cart/c-1/state", "admin", mapOf("If-None-Match" to alice.headers.eTag!!))
        revalidated.status.assert().isEqualTo(304)
        revalidated.body.assert().isNull()
    }

    @Test
    fun `without a snapshot query gateway the Manifest's masks still apply`() {
        val node = opened(QueryNode(manifest = sensitiveManifest(), snapshots = { null }))
        node.read("/shop/cart/c-1/state", "alice").body!!["state"]["note"].asString().assert().isEqualTo(DISPLAYED)
        node.read("/shop/cart/c-1/state?version=1", "alice").body!!["state"]["note"].asString()
            .assert().isEqualTo(DISPLAYED)
        node.read("/shop/cart/c-1/state/trace", "alice").body!!["commits"][0]["events"][0]["body"]["note"].asString()
            .assert().isEqualTo(CONFIDENTIAL)
    }

    @Test
    fun `a state whose schema declares nothing sensitive is read as it is`() {
        val node = opened(QueryNode())
        node.read("/shop/cart/c-1/state", "alice").body!!["state"]["note"].asString().assert().isEqualTo(SECRET)
        node.read("/shop/cart/c-1/state/trace", "alice").body!!["commits"][0]["events"][0]["body"]["note"].asString()
            .assert().isEqualTo(SECRET)
    }

    private companion object {
        const val SECRET = "hunter-2048"
        const val DISPLAYED = "hu*********"
        const val CONFIDENTIAL = "***********"

        /** [queryManifest] with the cart's `note` sensitive in its state and its `CartOpened` event. */
        fun sensitiveManifest(): Manifest {
            val manifest = queryManifest()
            fun sensitive(level: String, keepPrefix: Int) = mapOf(
                "type" to "string",
                "x-wow-sensitive" to mapOf("level" to level),
                "x-wow-mask" to mapOf("keepPrefix" to keepPrefix, "keepSuffix" to 0),
            )
            return manifest.copyWith(
                contexts = manifest.contexts.map { context ->
                    context.copyWith(
                        aggregates = context.aggregates.map {
                            it.copyWith(
                                events = listOf(
                                    EventManifest("CartOpened", schema = Manifest.schemaRef("CartOpened")),
                                    EventManifest("ItemAdded", schema = Manifest.schemaRef("ItemAdded")),
                                ),
                            )
                        },
                    )
                },
                defs = mapOf(
                    "Cart" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "items" to mapOf("type" to "integer"),
                            "note" to sensitive("DISPLAY", 2),
                        ),
                    ),
                    "CartOpened" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("note" to sensitive("CONFIDENTIAL", 0)),
                    ),
                    "ItemAdded" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("sku" to mapOf("type" to "string"), "quantity" to mapOf("type" to "integer")),
                    ),
                ),
            )
        }
    }
}
