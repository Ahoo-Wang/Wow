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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.AdaptiveLimitOptions
import me.ahoo.wow.query.AdaptiveQueryLimits
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test

/**
 * The query bounds that adapt to their store's write pressure, as the host configures them (decision D252): on by
 * default, off for a store whose `max-concurrent` is fixed, between the floor and the ceiling of the store's settings
 * over the global ones, over the provider's.
 */
class HostAdaptiveQueryLimitTest {
    private fun Host.stores(): QueryStores = context.getBean(QueryStores::class.java)

    @Test
    fun `a store's derived bound should adapt by default, up to its provider's ceiling`() {
        startHost().use { host ->
            val stores = host.stores()
            val limit = stores.limits.single()
            limit.bulkhead.assert().isSameAs(stores.bulkheads.getValue(ContextBackends.DEFAULT_STORE))
            limit.bulkhead.ceiling.assert().isEqualTo(StoreQueryProvider.DEFAULT_MAX_CONCURRENT)
            limit.floor.assert().isEqualTo(AdaptiveLimitOptions.DEFAULT_FLOOR)
            limit.options.floor.assert().isEqualTo(AdaptiveLimitOptions.DEFAULT_FLOOR)
            val limits = host.context.getBean(AdaptiveQueryLimits::class.java)
            limits.limits.assert().containsExactly(limit)
            // Stopped, so that only this test's evaluation takes the appends' sample.
            limits.close()
            // The pressure reads the appends the node measures on the store.
            repeat(
                5
            ) { host.post("/bank/account/open-account", """{"owner":"p-$it"}""").status.assert().isEqualTo(200) }
            limit.evaluate()
            limit.lastSample.appends.assert().isGreaterThanOrEqualTo(5)
            limit.lastSample.waitingForConnection.assert().isZero()
        }
    }

    @Test
    fun `the store's settings should take the place of the global ones, and a fixed bound should not adapt`() {
        startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.main.query.max-concurrent" to 5,
            "wow.stores.side.type" to "memory",
            "wow.stores.side.query.adaptive.ceiling" to 20,
            "wow.stores.side.query.adaptive.floor" to 4,
            "wow.stores.low.type" to "memory",
            "wow.query.adaptive.ceiling" to 8,
            "wow.log.store" to "main",
        ).use { host ->
            val stores = host.stores()
            val limits = stores.limits.associateBy { it.bulkhead.store }
            limits.keys.assert().containsExactlyInAnyOrder("side", "low")
            stores.bulkheads.getValue("main").ceiling.assert().isEqualTo(5)
            val side = limits.getValue("side")
            side.bulkhead.ceiling.assert().isEqualTo(20)
            side.bulkhead.limit.assert().isEqualTo(StoreQueryProvider.DEFAULT_MAX_CONCURRENT)
            side.floor.assert().isEqualTo(4)
            // A ceiling below the derived bound lowers the start to it, and the queue with it.
            val low = limits.getValue("low")
            low.bulkhead.ceiling.assert().isEqualTo(8)
            low.bulkhead.limit.assert().isEqualTo(8)
            low.bulkhead.maxQueued.assert().isEqualTo(8 * 4)
            low.floor.assert().isEqualTo(1)
            low.options.toString().assert().isEqualTo(AdaptiveLimitOptions().toString())
            // No log of the node is on it: its pressure is the store's connection waits alone.
            low.evaluate()
            low.lastSample.appends.assert().isZero()
        }
    }

    @Test
    fun `a global fixed bound should leave every bound fixed`() {
        startHost("wow.query.max-concurrent" to 3).use { host ->
            host.stores().limits.assert().isEmpty()
            host.stores().bulkheads.values.single().ceiling.assert().isEqualTo(3)
        }
        val zero = runCatching { startHost("wow.query.adaptive.ceiling" to 0).close() }.exceptionOrNull()
        generateSequence(zero) { it.cause }.any { it.message.orEmpty().contains("adaptive.ceiling") }
            .assert().isTrue()
    }

    @Test
    fun `a store without a log of the node should have no measured pressure`() {
        startHost().use { host ->
            val node = host.context.getBean(ServiceNode::class.java)
            node.writePressure("nowhere").assert().isNull()
            HostQueryBulkheads.pressureOf(node, "nowhere", null).sample().appends.assert().isZero()
            node.writePressure(ContextBackends.DEFAULT_STORE).assert().isNotNull()
        }
    }
}
