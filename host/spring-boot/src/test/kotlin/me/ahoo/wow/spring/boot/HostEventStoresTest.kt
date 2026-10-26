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
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.atomic.AtomicInteger

/** The `search` stores, which hold no log of the node's aggregates but serve `events` queries, as a search index does. */
@Configuration(proxyBeanMethods = false)
class SearchStores {
    @Bean
    fun searchStoreProvider(): StoreProvider = AcmeStoreProvider("search")

    @Bean
    fun searchQueryProvider(): StoreQueryProvider = SearchQueryProvider()
}

/** The queries of the `search` stores: `events` only, over an index of their own, counting the bindings it gives. */
class SearchQueryProvider : StoreQueryProvider {
    override val type: String = "search"

    override fun events(store: Store, options: StoreQueryOptions): EventQueries {
        val index = InMemoryEventQueryStorage.of(InMemoryEventLog(1))
        return EventQueries { type -> index.create(type).also { bindings.incrementAndGet() } }
    }

    override fun maxConcurrent(store: Store): Int = MAX_CONCURRENT

    companion object {
        const val MAX_CONCURRENT = 3
        val bindings = AtomicInteger()
    }
}

/**
 * An aggregate type's `events` queries routed to a store that is not its log store (decision D235 ④,
 * `wow.aggregates.[<context>.<aggregate>].query.events.store`): the Elasticsearch store an event search Sink writes.
 */
class HostEventStoresTest {
    private val account = AggregateType(BANK, "account")
    private val sources = listOf(HostTestApp::class.java, SearchStores::class.java)

    @Test
    fun `an aggregate's events queries run on the store its configuration names`() {
        val host = startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.search.type" to "search",
            "wow.log.store" to "main",
            "wow.query.memory.enabled" to "true",
            "wow.aggregates.[bank.account].query.events.store" to "search",
            sources = sources,
        )
        host.use {
            val events = host.context.getBean(EventQueryBackend::class.java)
            events.storeOf(account).assert().isEqualTo("search")
            val before = SearchQueryProvider.bindings.get()
            events.binding(account).assert().isNotNull()
            SearchQueryProvider.bindings.get().assert().isGreaterThan(before)
            // The queries run under the search store's bulkhead, from its provider's bound.
            val bulkhead: QueryBulkhead = host.context.getBean(QueryStores::class.java).bulkheads.getValue("search")
            bulkhead.toString().assert().contains("search")
            val id = host.post("/bank/account/open-account", """{"owner":"search"}""").body!!["ref"]["id"].asString()
            // The search index holds nothing the Sink did not write: the log's commit is not there.
            val found = host.post(
                "/bank/account/events/query",
                """{"kind":"list","filter":{"op":"ID","value":"$id"}}"""
            )
            found.status.assert().isEqualTo(200)
            found.body!!.size().assert().isZero()
        }
    }

    @Test
    fun `a type's events queries stay on its log store unless configured`() {
        val host = startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.search.type" to "search",
            "wow.log.store" to "main",
            "wow.query.memory.enabled" to "true",
            sources = sources,
        )
        host.use {
            host.context.getBean(EventQueryBackend::class.java).storeOf(account).assert().isEqualTo("main")
        }
    }

    @Test
    fun `a store the configuration names must exist`() {
        val failure = assertThrows<Exception> {
            startHost(
                "wow.stores.main.type" to "memory",
                "wow.log.store" to "main",
                "wow.aggregates.[bank.account].query.events.store" to "missing",
            ).close()
        }
        rootCause(failure).message.assert().contains("wow.aggregates.[bank.account].query.events.store is missing")
    }

    @Test
    fun `a store the configuration names must serve events queries`() {
        val failure = assertThrows<Exception> {
            startHost(
                "wow.stores.main.type" to "memory",
                "wow.stores.other.type" to "memory",
                "wow.log.store" to "main",
                "wow.aggregates.[bank.account].query.events.store" to "other",
            ).close()
        }
        rootCause(failure).message.assert().contains("whose events are not queried")
    }

    @Test
    fun `only the types that name a store are routed`() {
        HostEventStores.configured(null, setOf("main")).assert().isEmpty()
        val properties = WowProperties().apply {
            aggregates["bank.account"] = WowProperties.Aggregate().apply { query.events.store = "main" }
            aggregates["bank.card"] = WowProperties.Aggregate()
        }
        HostEventStores.configured(properties, setOf("main")).assert()
            .isEqualTo(mapOf(AggregateType(BANK, "account") to "main"))
        assertThrows<WowConfigurationException> {
            HostEventStores.configured(
                WowProperties().apply {
                    aggregates["bank."] = WowProperties.Aggregate().apply { query.events.store = "main" }
                },
                setOf("main"),
            )
        }
    }

    @Test
    fun `a key that names no aggregate type is refused`() {
        val failure = assertThrows<WowConfigurationException> {
            HostEventStores.configured(
                WowProperties().apply {
                    aggregates["account"] = WowProperties.Aggregate().apply { query.events.store = "main" }
                },
                setOf("main"),
            )
        }
        failure.message.assert().contains("names no aggregate type")
    }

    private fun rootCause(failure: Throwable): Throwable =
        generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.last()
}
