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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An aggregate type's `snapshot` queries routed to one of its snapshot stores that is not its primary
 * (`wow.aggregates.[<context>.<aggregate>].query.snapshot.store`): a search index of its states, say, while its
 * commands and its state reads stay on the primary.
 */
class HostSnapshotQueryStoresTest {
    private val account = AggregateType(BANK, "account")
    private val stores = arrayOf<Pair<String, Any>>(
        "wow.stores.main.type" to "memory",
        "wow.stores.search.type" to "memory",
        "wow.log.store" to "main",
        "wow.query.memory.enabled" to "true",
        "wow.aggregates.[bank.account].snapshot.stores" to "main, search",
    )

    @Test
    fun `an aggregate's snapshot queries read the snapshot store its configuration names`() {
        startHost(*stores, "wow.aggregates.[bank.account].query.snapshot.store" to "search").use { host ->
            host.context.getBean(SnapshotQueryBackend::class.java).storeOf(account).assert().isEqualTo("search")
            // The commands load from the primary, and the state reads read it.
            host.context.getBean(HostBackends::class.java).snapshotStoreOf(account)!!.name.assert().isEqualTo("main")
            val id = host.post("/bank/account/open-account", """{"owner":"search"}""").body!!["ref"]["id"].asString()
            host.get("/bank/account/$id/state").status.assert().isEqualTo(200)
            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"ID","value":"$id"}}""")
                .status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `an aggregate's snapshot queries read its primary unless configured`() {
        startHost(*stores).use { host ->
            host.context.getBean(SnapshotQueryBackend::class.java).storeOf(account).assert().isEqualTo("main")
        }
    }

    @Test
    fun `a store that is none of the type's snapshot stores is refused, naming the key`() {
        val failure = assertThrows<Exception> {
            startHost(
                *stores,
                "wow.stores.other.type" to "memory",
                "wow.aggregates.[bank.account].query.snapshot.store" to "other",
            ).close()
        }
        val refused = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>()
            .first()
        refused.key.assert().isEqualTo("wow.aggregates.[bank.account].query.snapshot.store")
        refused.message.assert().contains("other", "main", "search")
    }

    @Test
    fun `only the types that name a store are routed, each to one of its own`() {
        HostSnapshotQueryStores.configured(null) { listOf("main") }.assert().isEmpty()
        val wow = WowProperties().apply {
            aggregates["bank.account"] = WowProperties.Aggregate().apply { query.snapshot.store = "search" }
        }
        HostSnapshotQueryStores.configured(
            wow
        ) { listOf("main", "search") }.assert().isEqualTo(mapOf(account to "search"))
        assertThrows<WowConfigurationException> {
            HostSnapshotQueryStores.configured(
                WowProperties().apply {
                    aggregates["account"] = WowProperties.Aggregate().apply { query.snapshot.store = "search" }
                },
            ) { listOf("search") }
        }.key.assert().isEqualTo("wow.aggregates.[account]")
    }
}
