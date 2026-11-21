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

package me.ahoo.wow.cocache

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TenantSnapshotQueries
import me.ahoo.wow.spi.read.TenantQueryApi

/** A generated view, as an api module holds one: of the aggregate `shop.order`. */
@StateView("shop.order")
data class OrderView(val item: String, val quantity: Int? = null)

/** Another view of the same aggregate, which no cache may hold beside [OrderView]. */
@StateView("shop.order")
data class OtherOrderView(val item: String)

val ORDER = AggregateType("shop", "order")

/** The node's queries, as the owner answers them: [snapshots] for every tenant. */
fun queryApiOf(snapshots: TenantSnapshotQueries<OrderView>): TenantQueryApi = mockk {
    every { tenant(any()) } answers {
        mockk<SubscriptionQueries> { every { snapshots(OrderView::class.java) } returns snapshots }
    }
}

fun stateOf(id: String, version: Int, item: String, deleted: Boolean = false, tenantId: String = "t"): Snapshot<OrderView> =
    Snapshot.of(ORDER.ref(tenantId, id), version, Scope.NONE, emptyMap(), deleted, null, 0, 0, OrderView(item))

fun snapshotOf(id: String, version: Int, item: String, deleted: Boolean = false, tenantId: String = "t"): Snapshot<OrderView> =
    Snapshot.of(ORDER.ref(tenantId, id), version, Scope.NONE, emptyMap(), deleted, null, 0, 0, OrderView(item))

/** A node's CoCache runtime on [backend]: nodes that share a backend share the cache, as they share Redis. */
fun runtimeOn(backend: InMemoryBackend): CacheRuntime = CacheRuntime.builder().backend(backend).build()

/** A Manifest of the context `shop` and its aggregate `order`, whose state the contract views as [OrderView]. */
fun shopManifest(view: String? = OrderView::class.java.name): me.ahoo.wow.manifest.Manifest =
    me.ahoo.wow.manifest.Manifest(
        contexts = listOf(
            me.ahoo.wow.manifest.ContextManifest(
                name = "shop",
                aggregates = listOf(
                    me.ahoo.wow.manifest.AggregateManifest(
                        name = "order",
                        state = me.ahoo.wow.manifest.StateManifest(
                            me.ahoo.wow.manifest.Manifest.schemaRef("shop.OrderState"),
                            view = view,
                        ),
                    ),
                ),
            ),
        ),
        defs = mapOf("shop.OrderState" to mapOf("type" to "object")),
    )

/** A class loader whose class path carries [contract] as an api jar's peer contract, and nothing else of the kind. */
class ContractClassLoader(contract: me.ahoo.wow.manifest.Manifest, parent: ClassLoader) : ClassLoader(parent) {
    private val file: java.io.File = java.io.File.createTempFile("contract", ".json").apply {
        deleteOnExit()
        writeBytes(
            me.ahoo.wow.spi.json.WowJson.mapper.writeValueAsBytes(me.ahoo.wow.manifest.ManifestTree.write(contract))
        )
    }

    override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
        if (name == me.ahoo.wow.manifest.PeerContracts.RESOURCE) {
            java.util.Collections.enumeration(listOf(file.toURI().toURL()))
        } else {
            super.getResources(name)
        }
}

/** A generated view of the aggregate `shop.customer`, whose state holds a `DISPLAY` and a `CONFIDENTIAL` field. */
@StateView("shop.customer")
data class CustomerView(val name: String, val phone: String, val secret: String)

val CUSTOMER = AggregateType("shop", "customer")

/**
 * A Manifest of the context `shop` and its aggregate `customer`, whose snapshots are queried: its state's `phone` is
 * `@Sensitive(DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 4))`, its `secret` `@Sensitive(CONFIDENTIAL)` with
 * [secretMask] (the built-in mask, keeping nothing, by default).
 */
fun customerManifest(secretMask: Map<String, Any?> = emptyMap()): me.ahoo.wow.manifest.Manifest =
    me.ahoo.wow.manifest.Manifest(
        contexts = listOf(
            me.ahoo.wow.manifest.ContextManifest(
                name = "shop",
                aggregates = listOf(
                    me.ahoo.wow.manifest.AggregateManifest(
                        name = "customer",
                        state = me.ahoo.wow.manifest.StateManifest(
                            me.ahoo.wow.manifest.Manifest.schemaRef("shop.CustomerState"),
                            view = CustomerView::class.java.name,
                        ),
                        queries = listOf(me.ahoo.wow.manifest.QueryManifest(me.ahoo.wow.manifest.QueryRoutes.SNAPSHOT)),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "shop.CustomerState" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "phone" to mapOf("\$ref" to "#/\$defs/shop.Phone"),
                    "secret" to buildMap<String, Any?> {
                        put("type", "string")
                        put("x-wow-sensitive", mapOf("level" to "confidential"))
                        if (secretMask.isNotEmpty()) put("x-wow-mask", secretMask)
                    },
                ),
            ),
            // Behind a reference, as a value class carrying @Sensitive is written.
            "shop.Phone" to mapOf(
                "type" to "string",
                "x-wow-sensitive" to mapOf("level" to "display"),
                "x-wow-mask" to mapOf("keepPrefix" to 3, "keepSuffix" to 4),
            ),
        ),
    )

/** A commit of [ref] at [version] that leaves its state as [state], a JSON text; a deleting one when it is null. */
fun recordOf(
    ref: me.ahoo.wow.api.aggregate.AggregateRef,
    version: Int,
    state: String?
): me.ahoo.wow.spi.state.StateEventRecord =
    me.ahoo.wow.spi.state.StateEventRecord.of(
        ref = ref,
        version = version,
        commandId = "c-$version",
        requestId = "r-$version",
        commandType = "${ref.type}.Change",
        commitTime = 0,
        events = listOf(
            me.ahoo.wow.spi.state.StateEventPayload.of("Changed", 1, me.ahoo.wow.spi.json.WowJson.mapper.readTree("{}"))
        ),
        state = state?.let {
            me.ahoo.wow.spi.state.PublishedState.of(version, "f", me.ahoo.wow.spi.json.WowJson.mapper.readTree(it))
        },
        deleted = state == null,
    )

/** The state masks of a node whose types mask nothing, as a test context without the query side declares them. */
val NO_MASKS: me.ahoo.wow.spi.read.StateMasks = me.ahoo.wow.spi.read.StateMasks { null }
