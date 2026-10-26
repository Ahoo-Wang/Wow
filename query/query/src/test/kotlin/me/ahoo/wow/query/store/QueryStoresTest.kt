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

package me.ahoo.wow.query.store

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.query.AdaptiveLimitOptions
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** The query bounds of a node's stores, composed in `wow-query` for every host (reviews R3.4-2, R5.C-2). */
class QueryStoresTest {
    private val main = store("main", "acme")
    private val reports = store("reports", "acme")
    private val plain = store("plain", "none")
    private val stores = mapOf("main" to main, "reports" to reports, "plain" to plain)

    /** The provider of `acme` stores: 6 from its pool, up to 12. */
    private val provider = object : StoreQueryProvider {
        override val type: String = "acme"

        override fun maxConcurrent(store: Store): Int = 6

        override fun maxConcurrentCeiling(store: Store): Int = 12
    }

    private fun of(settings: QueryStoreSettings = QueryStoreSettings()) =
        QueryStores.of(settings, stores, { store -> provider.takeIf { store.type == "acme" } })

    @Test
    fun `a store's bound should come from it, else the global one, else its provider, and 0 should set none`() {
        val fixed = of(
            QueryStoreSettings(
                maxConcurrent = 3,
                stores = mapOf(
                    "reports" to QueryStoreLimits(maxConcurrent = 5, maxWait = 50.milliseconds, maxQueued = 7)
                ),
            ),
        )
        fixed.bulkheads.keys.assert().containsExactlyInAnyOrder("main", "reports")
        fixed.bulkheads.getValue("main").maxConcurrent.assert().isEqualTo(3)
        fixed.bulkheads.getValue("main").maxQueued.assert().isEqualTo(3 * QueryBulkhead.DEFAULT_QUEUE_FACTOR)
        with(fixed.bulkheads.getValue("reports")) {
            maxConcurrent.assert().isEqualTo(5)
            maxWait.assert().isEqualTo(50.milliseconds)
            maxQueued.assert().isEqualTo(7)
        }
        // A fixed bound does not adapt.
        fixed.limits.assert().isEmpty()
        of(QueryStoreSettings(maxConcurrent = 0)).bulkheads.assert().isEmpty()
    }

    @Test
    fun `a bound no setting fixes should start at the provider's and adapt up to the ceiling`() {
        val derived = of(
            QueryStoreSettings(
                adaptive = AdaptiveLimitOptions(floor = 2),
                stores = mapOf("reports" to QueryStoreLimits(floor = 3, ceiling = 4)),
            ),
        )
        with(derived.bulkheads.getValue("main")) {
            maxConcurrent.assert().isEqualTo(6)
            ceiling.assert().isEqualTo(12)
        }
        with(derived.bulkheads.getValue("reports")) {
            maxConcurrent.assert().isEqualTo(4)
            ceiling.assert().isEqualTo(4)
        }
        derived.limits.map { it.bulkhead.store }.assert().containsExactlyInAnyOrder("main", "reports")
    }

    @Test
    fun `a bound below zero or a ceiling below one should be refused with its key`() {
        val negative = runCatching {
            of(QueryStoreSettings(stores = mapOf("main" to QueryStoreLimits(maxConcurrent = -1))))
        }.exceptionOrNull() as WowConfigurationException
        negative.key.assert().isEqualTo("wow.stores.main.query.max-concurrent")
        negative.value.assert().isEqualTo(-1)
        val ceiling = runCatching { of(QueryStoreSettings(adaptiveCeiling = 0)) }.exceptionOrNull()
            as WowConfigurationException
        ceiling.key.assert().isEqualTo("wow.stores.main.query.adaptive.ceiling")
    }

    @Test
    fun `the dataset queries should run under their own bound`() {
        with(of().datasets!!) {
            store.assert().isEqualTo(QueryStores.DATASETS)
            maxConcurrent.assert().isEqualTo(DatasetQueryLimits.DEFAULT_MAX_CONCURRENT)
        }
        of(
            QueryStoreSettings(maxQueued = 9, datasets = DatasetQueryLimits(2))
        ).datasets!!.maxQueued.assert().isEqualTo(9)
        of(QueryStoreSettings(datasets = DatasetQueryLimits(0))).datasets.assert().isNull()
        of().all.map { it.store }.assert().containsExactlyInAnyOrder("main", "reports", QueryStores.DATASETS)
    }

    @Test
    fun `a replay should run under its log store's bulkhead, at once without one`() = runTest {
        val bounded = QueryStores(mapOf("main" to QueryBulkhead("main", 1)))
        bounded.replay("main") { 1 }.assert().isEqualTo(1)
        bounded.replay("other") { 2 }.assert().isEqualTo(2)
        bounded.replay(null) { 3 }.assert().isEqualTo(3)
        bounded.toString().assert().contains("main")
        QueryStores().all.assert().isEmpty()
        val type = AggregateType("shop", "order")
        QueryStores().unpinned(type).assert().isEqualTo(UnpinnedEvents.ALLOW)
        QueryStores(unpinned = { UnpinnedEvents.REFUSE }).unpinned(type).assert().isEqualTo(UnpinnedEvents.REFUSE)
    }

    private fun store(name: String, type: String): Store = object : Store(StoreConfig.of(name, type)) {
        override val location: Any = Any()

        override fun openCheckpoints() = error("unused")

        override fun openLeases() = error("unused")

        override fun openLedger() = error("unused")

        override fun openReservations() = error("unused")

        override fun openLog(context: String, partitions: Int) = error("unused")

        override fun openSnapshots(context: String) = error("unused")

        override fun openTimers(context: String, partitions: Int) = error("unused")
    }
}
