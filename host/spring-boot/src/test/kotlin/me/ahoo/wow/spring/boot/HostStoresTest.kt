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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.store.StoreLayout
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.TailPolling
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Opened
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The store instances of a node and where each aggregate's log and snapshots are (decision D177). */
class HostStoresTest {
    private val models = BankModels().models(Dependencies.of(DecidedCommands()))
    private val account = AggregateType(BANK, "account")

    private fun memory(vararg names: String): Map<String, Store> = names.associateWith { MemoryStore(it) }

    private val providers = StoreProviders.load(javaClass.classLoader)

    private fun open(
        properties: WowProperties,
        beans: DefaultListableBeanFactory,
        service: String = "",
        memoryOptIn: String? = null,
    ): Map<String, Store> = StoreInstances.open(properties, providers, BeanStoreResources(beans), service, memoryOptIn)

    private fun properties(block: WowProperties.() -> Unit = {}): WowProperties = WowProperties().apply(block)

    private fun layoutOf(properties: WowProperties, stores: Map<String, Store>, models: List<AggregateModel<*>>) =
        StoreLayout.of(properties.storePlacements(), stores, models.map { it.type })

    @Test
    fun `the only store holds every log and every snapshot`() {
        val layout = layoutOf(properties(), memory("only"), models)
        layout.log.assert().isEqualTo("only")
        layout.snapshot.assert().isEqualTo("only")
        layout.logStoreOf(account).assert().isEqualTo("only")
        layout.snapshotStoreOf(account).assert().isEqualTo("only")
        layout.logStores.assert().containsExactly("only")
    }

    @Test
    fun `an aggregate type's log and snapshots go to the stores it names`() {
        val layout = layoutOf(
            properties {
                log.store = "a"
                snapshot.stores = mutableListOf("b")
                aggregates["$BANK.account"] = WowProperties.Aggregate().apply {
                    log.store = "c"
                    snapshot.stores = mutableListOf("a")
                }
                aggregates["$BANK.other"] = WowProperties.Aggregate().apply { tenant = "fixed" }
            },
            memory("a", "b", "c"),
            models,
        )
        layout.logStoreOf(account).assert().isEqualTo("c")
        layout.snapshotStoreOf(account).assert().isEqualTo("a")
        layout.logStoreOf(AggregateType(BANK, "other")).assert().isEqualTo("a")
        layout.logStores.assert().containsExactlyInAnyOrder("a", "c")
        layout.snapshotStores.assert().containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `snapshots may have several targets, by default or per type, each with its primary (D172)`() {
        val layout = layoutOf(
            properties {
                log.store = "a"
                snapshot.stores = mutableListOf("a", "b")
                snapshot.primary = "b"
                aggregates["$BANK.account"] = WowProperties.Aggregate().apply {
                    snapshot.stores = mutableListOf("c", "a")
                }
            },
            memory("a", "b", "c"),
            models,
        )
        layout.snapshotDefault.assert().isEqualTo(SnapshotPlacement(listOf("a", "b"), "b"))
        layout.snapshotPlacementOf(account).assert().isEqualTo(SnapshotPlacement(listOf("c", "a"), "c"))
        layout.snapshotStoreOf(account).assert().isEqualTo("c")
        layout.snapshotStores.assert().containsExactlyInAnyOrder("a", "b", "c")
        val stores = memory("a", "b", "c")
        val targets = checkNotNull(layout.open(BANK, stores, 4).snapshots)
        targets.default.assert().isEqualTo("a")
        targets.stores.keys.assert().containsExactlyInAnyOrder("a", "b", "c")
        targets.primaryOf(account).assert().isSameAs(stores.getValue("c").snapshots(BANK))
        // A type that names its primary alone keeps the default targets.
        layoutOf(
            properties {
                log.store = "a"
                snapshot.stores = mutableListOf("a", "b")
                aggregates["$BANK.account"] = WowProperties.Aggregate().apply { snapshot.primary = "b" }
            },
            memory("a", "b"),
            models,
        ).snapshotPlacementOf(account).assert().isEqualTo(SnapshotPlacement(listOf("a", "b"), "b"))
        assertThrows<WowConfigurationException> {
            layoutOf(properties { snapshot.stores = mutableListOf("z") }, memory("a"), models)
        }.key.assert().isEqualTo("wow.snapshot.stores")
        assertThrows<WowConfigurationException> {
            layoutOf(
                properties {
                    log.store = "a"
                    snapshot.primary = "b"
                },
                memory("a", "b"),
                models
            )
        }.key.assert().isEqualTo("wow.snapshot.primary")
        assertThrows<IllegalStateException> {
            layoutOf(
                properties {
                    log.store = "a"
                    aggregates["$BANK.account"] = WowProperties.Aggregate().apply {
                        snapshot.stores = mutableListOf("a")
                        snapshot.primary = "b"
                    }
                },
                memory("a", "b"),
                models,
            )
        }.message.assert().contains("primary store b is not one of its targets")
    }

    @Test
    fun `a store that only holds a copy is never a type's primary snapshot store, nor its only one`() {
        val stores = memory("a") + (
            "search" to object : AcmeStore(StoreConfig("search", "search")) {
                override val primarySnapshots: Boolean = false
            }
            )
        layoutOf(
            properties {
                log.store = "a"
                aggregates["$BANK.account"] = WowProperties.Aggregate().apply { snapshot.stores = mutableListOf("a", "search") }
            },
            stores,
            models,
        ).snapshotPlacementOf(account).primary.assert().isEqualTo("a")
        assertThrows<IllegalStateException> {
            layoutOf(
                properties {
                    log.store = "a"
                    aggregates["$BANK.account"] = WowProperties.Aggregate().apply { snapshot.stores = mutableListOf("search") }
                },
                stores,
                models,
            )
        }.message.assert().contains(
            "The snapshot placement makes search",
            "List a mongo or postgres store first",
            "query search through the aggregate type's query snapshot store",
        )
        assertThrows<WowConfigurationException> {
            layoutOf(
                properties {
                    log.store = "a"
                    snapshot.stores = mutableListOf("a", "search")
                    snapshot.primary = "search"
                },
                stores,
                models,
            )
        }.key.assert().isEqualTo("wow.snapshot.primary")
    }

    @Test
    fun `the snapshot maintenance options follow the interval`() {
        val options = WowProperties.Snapshot().apply {
            interval = java.time.Duration.ofMinutes(1)
            batch = 7
            pages = 3
        }.options()
        options.interval.inWholeSeconds.assert().isEqualTo(60)
        options.batch.assert().isEqualTo(7)
        options.pages.assert().isEqualTo(3)
        options.leaseTtl.inWholeMinutes.assert().isEqualTo(3)
        WowProperties.Snapshot().options().leaseTtl.assert().isEqualTo(SnapshotOptions.DEFAULT_TTL)
    }

    @Test
    fun `a layout that does not add up is refused`() {
        val two = memory("a", "b")
        assertThrows<WowConfigurationException> { layoutOf(properties(), two, models) }.key.assert()
            .isEqualTo("wow.log.store")
        assertThrows<WowConfigurationException> { layoutOf(properties { log.store = "c" }, two, models) }
            .key.assert().isEqualTo("wow.log.store")
        assertThrows<WowConfigurationException> {
            layoutOf(
                properties {
                    log.store = "a"
                    snapshot.stores = mutableListOf("c")
                },
                two,
                models
            )
        }.key.assert().isEqualTo("wow.snapshot.stores")
        assertThrows<IllegalStateException> {
            layoutOf(
                properties {
                    log.store = "a"
                    aggregates["$BANK.ghost"] = WowProperties.Aggregate().apply { log.store = "b" }
                },
                two,
                models,
            )
        }.message.assert().contains("$BANK.ghost")
        assertThrows<WowConfigurationException> {
            layoutOf(
                properties {
                    log.store = "a"
                    aggregates["$BANK.account"] = WowProperties.Aggregate().apply { snapshot.stores = mutableListOf("z") }
                },
                two,
                models,
            )
        }.key.assert().isEqualTo("wow.aggregates.[$BANK.account].snapshot.stores")
        assertThrows<IllegalArgumentException> { layoutOf(properties(), emptyMap(), models) }
    }

    @Test
    fun `a store that holds no log is refused as a log store`() {
        val stores = mapOf("auto" to Unopened("auto", "acme", holdsLogs = false), "a" to MemoryStore("a"))
        assertThrows<IllegalStateException> { layoutOf(properties { log.store = "auto" }, stores, models) }
            .message.assert().contains("holds no log")
    }

    @Test
    fun `two log stores on one database are refused`() {
        val database = Any()
        val stores =
            mapOf(
                "a" to Unopened("a", "postgres", database),
                "b" to Unopened("b", "postgres", database)
            )
        assertThrows<IllegalStateException> {
            layoutOf(
                properties {
                    log.store = "a"
                    aggregates["$BANK.account"] = WowProperties.Aggregate().apply { log.store = "b" }
                },
                stores,
                models,
            )
        }.message.assert().contains("one database")
        // Snapshots may share one with a log.
        layoutOf(
            properties {
                log.store = "a"
                snapshot.stores = mutableListOf("b")
            },
            stores,
            models
        ).snapshot.assert().isEqualTo("b")
    }

    @Test
    fun `a context's stores are opened as the layout says, a log group per other log store`(): Unit = runBlocking {
        val stores = memory("a", "b", "c")
        val other = AggregateType(BANK, "other")
        val layout = StoreLayout(
            "a",
            "a",
            logs = mapOf(account to "b", other to "a", AggregateType("ledger", "entry") to "c"),
            snapshots = mapOf(account to "c", other to "a"),
        )
        val backends = HostBackends.of(stores, layout, 4)
        val bank = backends.factory.backends(BANK)
        bank.store.assert().isEqualTo("a")
        bank.log.assert().isSameAs(stores.getValue("a").log(BANK, 4))
        bank.checkpoints.assert().isSameAs(stores.getValue("a").checkpoints)
        bank.groups.single().store.assert().isEqualTo("b")
        bank.groups.single().types.assert().containsExactly(account)
        bank.groups.single().log.assert().isSameAs(stores.getValue("b").log(BANK, 4))
        bank.groups.single().checkpoints.assert().isSameAs(stores.getValue("b").checkpoints)
        bank.groups.single().timers.assert().isSameAs(stores.getValue("b").timers(BANK, 4))
        bank.snapshots?.primaryOf(account).assert().isSameAs(stores.getValue("c").snapshots(BANK))
        backends.factory.backends("ledger").groups.single().store.assert().isEqualTo("c")
        backends.snapshotStoreOf(account)?.name.assert().isEqualTo("c")
        backends.snapshotStoreOf(other)?.name.assert().isEqualTo("a")
        backends.type.assert().isEqualTo("memory")
        backends.local.assert().isTrue()
        backends.createSchemas()
        bank.log.read(account.ref("t", "x")).toList().assert().isEmpty()
        HostBackends.of { error("unused") }.snapshotStoreOf(account).assert().isNull()
    }

    @Test
    fun `a host on several durable stores is of its default log store's type`() {
        val other = Unopened("q", "acme")
        val postgres = Unopened("p", "postgres")
        val layout = StoreLayout("p", logs = mapOf(account to "q"))
        HostBackends.of(mapOf("q" to other, "p" to postgres), layout, 1).let {
            it.type.assert().isEqualTo("postgres")
            it.local.assert().isFalse()
        }
    }

    @Test
    fun `the stores of wow-stores are opened by their types`() {
        val beans = DefaultListableBeanFactory()
        val stores = open(
            properties {
                stores["a"] = WowProperties.Store().apply { type = "memory" }
                stores["b"] = WowProperties.Store().apply { type = "MEMORY" }
            },
            beans,
        )
        stores.mapValues { it.value.type }.assert().isEqualTo(mapOf("a" to "memory", "b" to "memory"))
        open(properties(), beans, memoryOptIn = "profile dev").keys.assert()
            .containsExactly(ContextBackends.DEFAULT_STORE)
    }

    @Test
    fun `auto opens the durable backend and refuses memory without an opt-in`() {
        val beans = DefaultListableBeanFactory()
        // Review R3.5-1: no DataSource and no MongoClient, outside a development node.
        assertThrows<IllegalStateException> {
            open(properties(), beans)
        }.message.assert().contains("No durable backend")
        open(properties { backend = "memory" }, beans).values.single().type.assert().isEqualTo("memory")
        beans.registerBeanDefinition("dataSource", RootBeanDefinition(Unpooled::class.java))
        open(properties(), beans).values.single().type.assert().isEqualTo("postgres")
    }

    @Test
    fun `the stores' logs poll as wow-log says`() {
        // Finding A2-14: the tails polled every 50 ms, idle or not, whatever the host said.
        val beans = DefaultListableBeanFactory()
        val stores = open(
            properties {
                log.pollInterval = java.time.Duration.ofMillis(20)
                log.maxPollInterval = java.time.Duration.ofSeconds(2)
                stores["a"] = WowProperties.Store().apply { type = "memory" }
            },
            beans,
        )
        stores.getValue("a").config.polling.assert().isEqualTo(TailPolling(20.milliseconds, 2.seconds))
        // Each store keeps the ledger of the service it was opened for (finding A2-13).
        open(properties(), beans, service = "orders", memoryOptIn = "dev").values.single().config.service
            .assert().isEqualTo("orders")
        open(properties(), beans, memoryOptIn = "dev").values.single().config.polling
            .assert().isEqualTo(TailPolling.DEFAULT)
        assertThrows<IllegalArgumentException> {
            WowProperties.Log().apply { maxPollInterval = java.time.Duration.ofMillis(10) }.tailPolling()
        }
        assertThrows<IllegalArgumentException> { TailPolling(kotlin.time.Duration.ZERO, 1.seconds) }
    }

    @Test
    fun `a store that cannot be opened is refused`() {
        val beans = DefaultListableBeanFactory()
        fun open(block: WowProperties.() -> Unit) = assertThrows<IllegalStateException> {
            open(properties(block), beans)
        }.message!!
        open { stores["a"] = WowProperties.Store() }.assert().contains("wow.stores.a.type is not set")
        open { stores["a"] = WowProperties.Store().apply { type = "auto" } }.assert().contains("is auto")
        open {
            backend = "memory"
            stores["a"] = WowProperties.Store().apply { type = "memory" }
        }.assert().contains("wow.backend is memory")
        open { stores["a"] = WowProperties.Store().apply { type = "nope" } }.assert()
            .contains("No store provider offers the store type nope: memory")
        open { stores["a"] = WowProperties.Store().apply { type = "postgres" } }.assert()
            .contains("no DataSource bean")
        open { stores["a"] = WowProperties.Store().apply { type = "mongo" } }.assert()
            .contains("no MongoClient bean")
        open {
            stores["a"] = WowProperties.Store().apply {
                type = "postgres"
                dataSource = "missing"
            }
        }.assert().contains("names missing, which is no DataSource bean")
    }

    @Test
    fun `a named bean backs its store`() {
        val beans = DefaultListableBeanFactory()
        beans.registerBeanDefinition("first", RootBeanDefinition(Unpooled::class.java))
        beans.registerBeanDefinition("second", RootBeanDefinition(Unpooled::class.java))
        val stores = open(
            properties {
                stores["a"] = WowProperties.Store().apply {
                    type = "postgres"
                    dataSource = "second"
                }
            },
            beans,
        )
        stores.getValue("a").location.assert().isSameAs(beans.getBean("second"))
        // Only the options of its own type: a PostgreSQL store is given no MongoDB database.
        stores.getValue(
            "a"
        ).config.options.assert().containsEntry("data-source", "second").doesNotContainKey("database")
        stores.getValue("a").toString().assert().isEqualTo("a (postgres)")
    }

    @Test
    fun `a type whose log store keeps its primary snapshots should get that store's read of both together`() {
        val tailing = Tailing("a")
        val search = Unopened("b", "search", holdsLogs = false)
        val layout = StoreLayout(
            "a",
            placements = mapOf(account to SnapshotPlacement(listOf("b", "a"), "b")),
        )
        val bank = layout.open(BANK, mapOf("a" to tailing, "b" to search), 4)
        // The default placement keeps the snapshots on the log's store: read together.
        bank.snapshotTails.of(AggregateType(BANK, "other")).assert().isSameAs(tailing.reader)
        // The account's primary snapshots are on another store: read apart.
        bank.snapshotTails.of(account).assert().isNull()
        bank.stores.writePath.assert().containsExactlyInAnyOrder(tailing, search)
        bank.stores.others.assert().isEmpty()
    }

    @Test
    fun `a host serves an aggregate whose log is on a store of its own`() {
        val host = startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.accounts.type" to "memory",
            "wow.log.store" to "main",
            "wow.aggregates.[bank.account].log.store" to "accounts",
            "wow.aggregates.[bank.account].snapshot.stores" to "accounts",
        )
        host.use {
            val opened = host.post("/bank/account/open-account", """{"owner":"store"}""")
            opened.status.assert().isEqualTo(200)
            val id = opened.body!!["ref"]["id"].asString()
            host.get("/bank/account/$id/state").body!!["state"]["owner"].asString().assert().isEqualTo("store")
            val backends = host.context.getBean(HostBackends::class.java)
            val context = host.node.context(BANK)
            context.logGroups.map { it.store }.assert().containsExactly("main", "accounts")
            context.logGroups.last().types.assert().containsExactly(account)
            val ref = account.ref(Caller.DEFAULT_TENANT, id)
            runBlocking {
                context.logGroups.last().log.read(ref).toList().assert().hasSize(1)
                context.logGroups.first().log.read(ref).toList().assert().isEmpty()
            }
            backends.snapshotStoreOf(account)?.name.assert().isEqualTo("accounts")
        }
    }

    /** A store whose parts are never opened: for the layout's checks. */
    /** A store in memory that reads its snapshots with its log's tails in one round trip, as PostgreSQL does. */
    private class Tailing(name: String) : Store(StoreConfig(name, "tailing")) {
        val reader = SnapshotTailReader { _, _ -> error("unused") }
        override val location: Any = Any()

        override fun openSnapshotTails(context: String): SnapshotTailReader = reader

        override fun openCheckpoints(): Opened<CheckpointStore> = Opened.of(InMemoryCheckpointStore())

        override fun openLeases(): Opened<LeaseStore> = Opened.of(InMemoryLeaseStore())

        override fun openLedger(): Opened<FailureLedger> = Opened.of(InMemoryFailureLedger())

        override fun openReservations(): Opened<ReservationStore> = Opened.of(InMemoryReservationStore())

        override fun openLog(context: String, partitions: Int): Opened<EventLog> = Opened.of(
            InMemoryEventLog(partitions)
        )

        override fun openSnapshots(context: String): Opened<SnapshotStore> = Opened.of(InMemorySnapshotStore())

        override fun openTimers(context: String, partitions: Int): Opened<TimerStore> = Opened.of(InMemoryTimerStore())
    }

    private class Unopened(
        name: String,
        type: String,
        override val location: Any = Any(),
        override val holdsLogs: Boolean = true,
    ) : Store(StoreConfig(name, type)) {
        override fun openCheckpoints(): Opened<CheckpointStore> = error("unused")

        override fun openLeases(): Opened<LeaseStore> = error("unused")

        override fun openLedger(): Opened<FailureLedger> = error("unused")

        override fun openReservations(): Opened<ReservationStore> = error("unused")

        override fun openLog(context: String, partitions: Int): Opened<EventLog> = error("unused")

        override fun openSnapshots(context: String): Opened<SnapshotStore> = Opened.of(InMemorySnapshotStore())

        override fun openTimers(context: String, partitions: Int): Opened<TimerStore> = error("unused")
    }

    /** A `DataSource` bean that is never connected. */
    class Unpooled : DataSource by (
        java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, _, _ -> error("unused") } as DataSource
        )
}
