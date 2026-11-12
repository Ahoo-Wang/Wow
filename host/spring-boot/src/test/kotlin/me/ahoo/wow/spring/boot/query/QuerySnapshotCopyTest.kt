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

package me.ahoo.wow.spring.boot.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.BANK
import me.ahoo.wow.spring.boot.startHost
import me.ahoo.wow.spring.boot.store.HostBackends
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An aggregate type's `snapshot` queries on another store than its own (`wow.aggregates.[<context>.<aggregate>]
 * .query.snapshot.store`, D423): that store keeps a copy of its snapshots, written by the store's subscription, while its
 * commands and its state reads stay on its own store.
 */
class QuerySnapshotCopyTest {
    private val account = AggregateType(BANK, "account")
    private val stores = arrayOf<Pair<String, Any>>(
        "wow.stores.main.type" to "memory",
        "wow.stores.search.type" to "memory",
        "wow.log.store" to "main",
    )

    @Test
    fun `an aggregate's snapshot queries on another store make that store its copy`() {
        startHost(*stores, "wow.aggregates.[bank.account].query.snapshot.store" to "search").use { host ->
            host.context.getBean(SnapshotQueryBackend::class.java).storeOf(account).assert().isEqualTo("search")
            // The commands load from the type's own store, and the state reads read it.
            host.context.getBean(HostBackends::class.java).snapshotStoreOf(account)!!.name.assert().isEqualTo("main")
            host.context.getBean(ServiceNode::class.java).context(BANK).snapshotSubscriptions.map { it.name }.assert()
                .containsExactly("wow.snapshot", "wow.snapshot:search")
            val id = host.post("/bank/account/open-account", """{"owner":"search"}""").body!!["ref"]["id"].asString()
            host.get("/bank/account/$id/state").status.assert().isEqualTo(200)
            host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"id","value":"$id"}}""")
                .status.assert().isEqualTo(200)
        }
    }

    @Test
    fun `an aggregate's snapshot queries read its own store, with no copy, unless configured`() {
        startHost(*stores).use { host ->
            host.context.getBean(SnapshotQueryBackend::class.java).storeOf(account).assert().isEqualTo("main")
            host.context.getBean(ServiceNode::class.java).context(BANK).snapshotSubscriptions.map { it.name }.assert()
                .containsExactly("wow.snapshot")
        }
    }

    @Test
    fun `the keys of the list shape refuse the node's start, each naming itself`() {
        listOf(
            "wow.snapshot.stores" to "main",
            "wow.snapshot.primary" to "main",
            "wow.aggregates.[bank.account].snapshot.stores" to "main, search",
            "wow.aggregates.[bank.account].snapshot.primary" to "main",
        ).forEach { removed ->
            val failure = assertThrows<Exception> { startHost(*stores, removed).close() }
            val refused = generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<WowConfigurationException>().first()
            refused.key.assert().isEqualTo(removed.first)
        }
    }

    @Test
    fun `a query snapshot store that is none of the node's stores is refused, naming the key`() {
        val failure = assertThrows<Exception> {
            startHost(*stores, "wow.aggregates.[bank.account].query.snapshot.store" to "other").close()
        }
        val refused = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>()
            .first()
        refused.key.assert().isEqualTo("wow.aggregates.[bank.account].query.snapshot.store")
        refused.message.assert().contains("other")
    }
}
