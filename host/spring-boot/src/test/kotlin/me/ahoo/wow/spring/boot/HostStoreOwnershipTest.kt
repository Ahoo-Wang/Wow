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
import me.ahoo.wow.spring.boot.query.SearchStore
import me.ahoo.wow.spring.boot.store.AcmeStoreProvider
import me.ahoo.wow.spring.boot.store.BeanStoreResources
import me.ahoo.wow.spring.boot.store.HostBackends
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
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

/** A pool the `pooled` stores are opened over, as a `DataSource` is: it counts its uses on its [meter]. */
class Pool(private val meter: PoolMeter) : AutoCloseable {
    @Volatile
    var open: Boolean = true
        private set

    fun use() = meter.count()

    override fun close() {
        open = false
        OwnedStore.record += "close:pool"
    }
}

/** What a [Pool] counts its uses on: a registration of its own, closed with the context. */
interface PoolMeter : AutoCloseable {
    fun count()
}

/**
 * The `pooled` stores, each over the [Pool] bean. The pool's meter is resolved on the pool's first use, after the node
 * started, so Spring creates the meter after the backends, and destroys it, with the pool that depends on it, first.
 */
@Configuration(proxyBeanMethods = false)
class PooledStores {
    @Bean
    fun pool(@Lazy meter: PoolMeter): Pool = Pool(meter)

    @Bean
    @Lazy
    fun poolMeter(): PoolMeter = object : PoolMeter {
        override fun count() = Unit

        override fun close() = Unit
    }

    @Bean
    fun pooledStoreProvider(): StoreProvider = object : AcmeStoreProvider("pooled") {
        override fun open(config: StoreConfig): Store {
            val pool = config.resources.resource(Pool::class.java, null, config.key)
            return object : OwnedStore(config) {
                override fun closeStore() {
                    super.closeStore()
                    record += "close:$name over ${if (pool.open) "an open" else "a closed"} pool"
                }
            }
        }
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

    @Test
    fun `a store closes before the resource bean it is opened over, whichever of the two Spring would destroy first`() {
        val host = startHost(
            "wow.stores.main.type" to "pooled",
            "wow.log.store" to "main",
            sources = sources + PooledStores::class.java,
        )
        host.context.getBean(Pool::class.java).use()
        host.close()
        OwnedStore.record.filterNot { it.startsWith("node:") }.assert()
            .containsExactly("open:main", "close:main", "close:main over an open pool", "close:pool")
    }

    /** A [PoolMeter] that counts nothing. */
    private val meter = object : PoolMeter {
        override fun count() = Unit

        override fun close() = Unit
    }

    @Test
    fun `a resource bean named for a store is declared a dependency of the bean it is given to`() {
        val beans = DefaultListableBeanFactory().apply {
            registerSingleton("main", Pool(meter))
            registerSingleton("other", Pool(meter))
        }
        val other = BeanStoreResources(beans, "sinks").resource(Pool::class.java, "other", "wow.stores.main.pool")
        other.assert().isSameAs(beans.getBean("other"))
        beans.getDependentBeans("other").assert().containsExactly("sinks")
        beans.getDependentBeans("main").assert().isEmpty()
    }

    @Test
    fun `a blank resource name should take the only bean of its type, and a bean of another type is refused`() {
        val beans = DefaultListableBeanFactory().apply {
            registerSingleton("main", Pool(meter))
            registerSingleton("text", "no pool")
        }
        val resources = BeanStoreResources(beans, "sinks")
        resources.resource(Pool::class.java, " ", "wow.stores.main.pool").assert().isSameAs(beans.getBean("main"))
        beans.getDependentBeans("main").assert().containsExactly("sinks")
        assertThrows<WowConfigurationException> {
            resources.resource(Pool::class.java, "text", "wow.stores.main.pool")
        }.apply {
            key.assert().isEqualTo("wow.stores.main.pool")
            message.assert().isEqualTo("wow.stores.main.pool names text, which is no Pool bean. Name a Pool bean.")
        }
        beans.getDependentBeans("text").assert().isEmpty()
    }

    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()
}
