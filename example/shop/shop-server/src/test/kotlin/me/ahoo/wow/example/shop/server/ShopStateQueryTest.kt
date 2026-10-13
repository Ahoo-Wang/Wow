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

package me.ahoo.wow.example.shop.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorageAdapter
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.spring.boot.HostBackends
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * The state of the shop's orders queried by its fields (`state.*`): the shop's domain module completes its Manifest
 * fragment with the state schemas (example/wow-manifest-fragment.gradle.kts, decision D73), so the Manifest the
 * running shop merged declares the snapshot model's `state` and an order is found by its status. The snapshots are the
 * ones the shop wrote on the memory backend, queried with the in-memory reference backend of wow-query's test fixtures.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopStateQueryTest {
    private val shop = startShop()

    @AfterAll
    fun close() {
        shop.close()
    }

    private fun schemaOf(manifest: Manifest, type: AggregateType): suspend () -> QueryModelSchema = {
        DefaultQueryModelSchemaProvider(
            QuerySchemaContext(type, QueryModel.SNAPSHOT),
            listOf(ManifestQuerySchemaSource(manifest)),
            InMemoryQueryStorageAdapter,
        ).schema()
    }

    private fun placeOrder(user: String): String {
        val created = shop.post(
            "/example/order/create-order",
            """{"items":[{"productId":"apple","price":2.50,"quantity":2}],"address":$ADDRESS}""",
            user,
        )
        created.status.assert().isEqualTo(200)
        return created.json["ref"]["id"].asString()
    }

    @Test
    fun `the order and cart states should be fields of their snapshot models`() {
        runTest {
            val manifest = shop.node.manifest
            val order = schemaOf(manifest, ORDER)()
            order.field(QueryField("state"))!!.value.kind.assert().isEqualTo(QueryValueKind.OBJECT)
            order.field(QueryField("state.status")).assert().isNotNull()
            order.field(QueryField("state.totalAmount")).assert().isNotNull()
            order.field(QueryField("state.items.productId")).assert().isNotNull()
            order.field(QueryField("state.address.city")).assert().isNotNull()
            val cart = schemaOf(manifest, CART)()
            cart.field(QueryField("state.items.quantity")).assert().isNotNull()
        }
    }

    @Test
    fun `an order should be found by its status`() {
        runTest { findByStatus() }
    }

    private suspend fun findByStatus() {
        val alice = "alice"
        val kept = placeOrder(alice)
        val cancelled = placeOrder(alice)
        shop.post("/example/order/$cancelled/cancel-order", """{"reason":"changed my mind"}""", alice)
            .status.assert().isEqualTo(200)

        val store = shop.context.getBean(HostBackends::class.java).contexts.getValue("example").snapshots!!
        val queries = InMemoryQueryStorage()
        // The snapshot subscription writes the snapshots after the commands: wait for the cancellation's.
        withContext(Dispatchers.Default) {
            withTimeout(SNAPSHOT_TIMEOUT_MILLIS) {
                mapOf(kept to 1, cancelled to 2).forEach { (id, version) ->
                    val ref = AggregateRef(ORDER, Caller.DEFAULT_TENANT, id)
                    while (store.load(ref)?.takeIf { it.version >= version }?.let(queries::save) == null) {
                        delay(POLL_MILLIS)
                    }
                }
            }
        }

        val schema = schemaOf(shop.node.manifest, ORDER)()
        val backend = queries.create(ORDER).backend
        val byStatus = { status: String ->
            QueryAdmission.Trusted.list(ListQuery(EqualFilter(QueryField("state.status"), status)), schema)
        }
        backend.stream(byStatus("CANCELLED")).toList().map { it["aggregateId"].asString() }
            .assert().containsExactly(cancelled)
        backend.stream(byStatus("CREATED")).toList().map { it["aggregateId"].asString() }
            .assert().containsExactly(kept)
        // A field the state does not have is still refused.
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.list(ListQuery(EqualFilter(QueryField("state.nope"), 1)), schema)
        }.violation?.code.assert().isEqualTo(QueryErrorCodes.UNKNOWN_FIELD)
    }

    private companion object {
        val ORDER = AggregateType("example", "order")
        val CART = AggregateType("example", "cart")
        const val POLL_MILLIS = 20L
        const val SNAPSHOT_TIMEOUT_MILLIS = 10_000L
        const val ADDRESS =
            """{"country":"China","province":"Shanghai","city":"Shanghai","district":"Pudong","detail":"1 Century Avenue"}"""
    }
}
