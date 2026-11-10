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
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The `owned` stores: each records when its provider opened it and when it was closed, in [OwnedStore.record]. A store
 * of the region `nowhere` fails to open, as one whose resource is missing does.
 */
@Configuration(proxyBeanMethods = false)
class OwnedStores {
    @Bean
    fun ownedStoreProvider(): StoreProvider = object : AcmeStoreProvider("owned") {
        override fun open(config: StoreConfig): Store {
            val region = config.option(REGION)
            configCheck(region != "nowhere", "${config.key}.region", region, "Name a region.") { "No such region." }
            return OwnedStore(config)
        }
    }

    /** Records each step of the node's stop beside the stores' openings and closes. */
    @Bean
    fun ownedStopSteps(): ServiceNodeListener = ServiceNodeListener { step -> OwnedStore.record += "node:$step" }
}

/** An `owned` store: it serves `events` queries, as a search index does, and records its opening and its closes. */
open class OwnedStore(config: StoreConfig) : SearchStore(config) {
    init {
        record += "open:$name"
    }

    override fun closeStore() {
        record += "close:$name"
    }

    companion object {
        val record: MutableList<String> = CopyOnWriteArrayList()
    }
}

/** A bean that fails once the node's backends exist, as any later bean of the application may. */
@Configuration(proxyBeanMethods = false)
class FailingAfterBackends {
    @Bean
    fun failingBean(backends: HostBackends): Any = error("Failed after ${backends.stores.keys}.")
}

/**
 * Whatever store the host opens, it owns and closes, once each, in reverse order of opening, whether the host started
 * and stopped or failed half-way, and whichever role the store was opened for: the record of every case below is
 * the stores' openings, then their closes in reverse.
 */
class HostStoreOwnershipTest {
    private val sources = listOf(HostTestApp::class.java, OwnedStores::class.java)

    @BeforeEach
    fun clear() {
        OwnedStore.record.clear()
    }

    /** Each of [opened] opened, then closed once, in reverse order of opening. */
    private fun closedInReverse(vararg opened: String) {
        OwnedStore.record.filterNot { it.startsWith("node:") }.assert().containsExactlyElementsOf(
            opened.map { "open:$it" } + opened.reversed().map { "close:$it" },
        )
    }

    @Test
    fun `a host that stops closes every store it opened, the one only events queries read too`() {
        startHost(
            "wow.stores.main.type" to "owned",
            "wow.stores.search.type" to "owned",
            "wow.log.store" to "main",
            "wow.aggregates.[bank.account].query.events.store" to "search",
            sources = sources,
        ).close()
        closedInReverse("main", "search")
        // Only once the node stopped, its logs closed last.
        val record = OwnedStore.record
        record.indexOf("node:${ServiceNode.Step.LOGS_CLOSED}").assert().isNotNegative()
            .isLessThan(record.indexOfFirst { it.startsWith("close:") })
    }

    @Test
    fun `a store that fails to open closes the stores opened before it, and the start fails with its error`() {
        val failure = assertThrows<Exception> {
            startHost(
                "wow.stores.main.type" to "owned",
                "wow.stores.snapshots.type" to "owned",
                "wow.stores.search.type" to "owned",
                "wow.stores.unopenable.type" to "owned",
                "wow.stores.unopenable.region" to "nowhere",
                "wow.log.store" to "main",
                "wow.snapshot.stores" to "snapshots",
                sources = sources,
            )
        }
        failure.causes().filterIsInstance<WowConfigurationException>().single().key.assert()
            .isEqualTo("wow.stores.unopenable.region")
        // Whichever stores opened before it, each is closed once, in reverse; the one that failed never opened.
        val opened = OwnedStore.record.filter { it.startsWith("open:") }.map { it.removePrefix("open:") }
        opened.assert().isNotEmpty().doesNotContain("unopenable")
        closedInReverse(*opened.toTypedArray())
    }

    @Test
    fun `a layout that fails closes every store opened for it`() {
        val failure = assertThrows<Exception> {
            startHost(
                "wow.stores.main.type" to "owned",
                "wow.stores.search.type" to "owned",
                "wow.log.store" to "nowhere",
                sources = sources,
            )
        }
        failure.causes().filterIsInstance<WowConfigurationException>().single().key.assert().isEqualTo("wow.log.store")
        closedInReverse("main", "search")
    }

    @Test
    fun `a later bean that fails closes every store the backends hold`() {
        assertThrows<Exception> {
            startHost(
                "wow.stores.main.type" to "owned",
                "wow.stores.search.type" to "owned",
                "wow.log.store" to "main",
                sources = sources + FailingAfterBackends::class.java,
            )
        }.causes().map { it.message }.assert().contains("Failed after [main, search].")
        closedInReverse("main", "search")
    }

    @Test
    fun `a store that fails to close leaves none of the others open, and its failure is thrown`() {
        val stores = listOf("a", "b", "c").associateWith { name ->
            object : OwnedStore(StoreConfig.of(name, "owned")) {
                override fun closeStore() {
                    super.closeStore()
                    check(name == "a") { "$name fails to close." }
                }
            }
        }
        val backends = HostBackends.of(stores, StoreLayout("a"), 1)
        val failure = assertThrows<IllegalStateException> { backends.close() }
        failure.message.assert().isEqualTo("c fails to close.")
        failure.suppressed.map { it.message }.assert().containsExactly("b fails to close.")
        closedInReverse("a", "b", "c")
        // Closing again closes nothing twice.
        backends.close()
        closedInReverse("a", "b", "c")
    }

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()
}
