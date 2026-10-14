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
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.log.AppendQueueOptions
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotStoreRouter
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import javax.sql.DataSource

/** The store instances of a node and where each aggregate's log and snapshots are (decision D177). */
class HostStoresTest {
    private val models = BankModels().models(Dependencies.of(DecidedCommands()))
    private val account = AggregateType(BANK, "account")

    private fun memory(vararg names: String): Map<String, HostStore> = names.associateWith { MemoryStore(it) }

    private fun properties(block: WowProperties.() -> Unit = {}): WowProperties = WowProperties().apply(block)

    @Test
    fun `the only store holds every log and every snapshot`() {
        val layout = StoreLayout.of(properties(), memory("only"), models)
        layout.log.assert().isEqualTo("only")
        layout.snapshot.assert().isEqualTo("only")
        layout.logStoreOf(account).assert().isEqualTo("only")
        layout.snapshotStoreOf(account).assert().isEqualTo("only")
        layout.logStores.assert().containsExactly("only")
    }

    @Test
    fun `an aggregate type's log and snapshots go to the stores it names`() {
        val layout = StoreLayout.of(
            properties {
                log.store = "a"
                snapshot.store = "b"
                aggregates["$BANK.account"] = WowProperties.Aggregate().apply {
                    log.store = "c"
                    snapshot.store = mutableListOf("a")
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
        val layout = StoreLayout.of(
            properties {
                log.store = "a"
                snapshot.targets = mutableListOf("a", "b")
                snapshot.primary = "b"
                aggregates["$BANK.account"] = WowProperties.Aggregate().apply {
                    snapshot.store = mutableListOf("c", "a")
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
        val targets = checkNotNull(layout.open(BANK, stores, 4).snapshotTargets)
        targets.default.assert().isEqualTo("a")
        targets.stores.keys.assert().containsExactlyInAnyOrder("a", "b", "c")
        targets.primaryOf(account).assert().isSameAs(stores.getValue("c").snapshots(BANK))
        // A type that names its primary alone keeps the default targets.
        StoreLayout.of(
            properties {
                log.store = "a"
                snapshot.targets = mutableListOf("a", "b")
                aggregates["$BANK.account"] = WowProperties.Aggregate().apply { snapshot.primary = "b" }
            },
            memory("a", "b"),
            models,
        ).snapshotPlacementOf(account).assert().isEqualTo(SnapshotPlacement(listOf("a", "b"), "b"))
        assertThrows<IllegalStateException> {
            StoreLayout.of(properties { snapshot.targets = mutableListOf("z") }, memory("a"), models)
        }.message.assert().contains("wow.snapshot.targets names the store z")
        assertThrows<IllegalStateException> {
            StoreLayout.of(
                properties {
                    log.store = "a"
                    snapshot.primary = "b"
                },
                memory("a", "b"),
                models
            )
        }.message.assert().contains("wow.snapshot.primary names b, which is not one of its targets")
        assertThrows<IllegalStateException> {
            StoreLayout.of(
                properties {
                    log.store = "a"
                    aggregates["$BANK.account"] = WowProperties.Aggregate().apply {
                        snapshot.store = mutableListOf("a")
                        snapshot.primary = "b"
                    }
                },
                memory("a", "b"),
                models,
            )
        }.message.assert().contains("wow.aggregates.[$BANK.account].snapshot.primary names b")
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
        assertThrows<IllegalStateException> { StoreLayout.of(properties(), two, models) }.message.assert()
            .contains("wow.log.store")
        assertThrows<IllegalStateException> { StoreLayout.of(properties { log.store = "c" }, two, models) }
            .message.assert().contains("wow.log.store names the store c")
        assertThrows<IllegalStateException> {
            StoreLayout.of(
                properties {
                    log.store = "a"
                    snapshot.store = "c"
                },
                two,
                models
            )
        }.message.assert().contains("wow.snapshot.store")
        assertThrows<IllegalStateException> {
            StoreLayout.of(
                properties {
                    log.store = "a"
                    aggregates["$BANK.ghost"] = WowProperties.Aggregate().apply { log.store = "b" }
                },
                two,
                models,
            )
        }.message.assert().contains("$BANK.ghost")
        assertThrows<IllegalStateException> {
            StoreLayout.of(
                properties {
                    log.store = "a"
                    aggregates["$BANK.account"] = WowProperties.Aggregate().apply { snapshot.store = mutableListOf("z") }
                },
                two,
                models,
            )
        }.message.assert().contains("wow.aggregates.[$BANK.account].snapshot.store")
        assertThrows<IllegalArgumentException> { StoreLayout.of(properties(), emptyMap(), models) }
    }

    @Test
    fun `a store that holds no log is refused as a log store`() {
        val stores = mapOf("auto" to Unopened("auto", BackendKind.AUTO), "a" to MemoryStore("a"))
        assertThrows<IllegalStateException> { StoreLayout.of(properties { log.store = "auto" }, stores, models) }
            .message.assert().contains("holds no log")
    }

    @Test
    fun `two log stores on one database are refused`() {
        val database = Any()
        val stores =
            mapOf(
                "a" to Unopened("a", BackendKind.POSTGRES, database),
                "b" to Unopened("b", BackendKind.POSTGRES, database)
            )
        assertThrows<IllegalStateException> {
            StoreLayout.of(
                properties {
                    log.store = "a"
                    aggregates["$BANK.account"] = WowProperties.Aggregate().apply { log.store = "b" }
                },
                stores,
                models,
            )
        }.message.assert().contains("one database")
        // Snapshots may share one with a log.
        StoreLayout.of(
            properties {
                log.store = "a"
                snapshot.store = "b"
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
        bank.groups.single().timers.assert().isSameAs(stores.getValue("b").timers(BANK))
        (bank.snapshots as SnapshotStoreRouter).storeOf(account).assert().isSameAs(stores.getValue("c").snapshots(BANK))
        bank.snapshots.assert().isInstanceOf(SnapshotStoreRouter::class.java)
        backends.factory.backends("ledger").groups.single().store.assert().isEqualTo("c")
        backends.snapshotStoreOf(account)?.name.assert().isEqualTo("c")
        backends.snapshotStoreOf(other)?.name.assert().isEqualTo("a")
        backends.kind.assert().isEqualTo(BackendKind.MEMORY)
        backends.appendQueue.assert().isEqualTo(AppendQueueOptions())
        backends.createSchemas()
        bank.log.read(account.ref("t", "x")).toList().assert().isEmpty()
        HostBackends.of { error("unused") }.snapshotStoreOf(account).assert().isNull()
    }

    @Test
    fun `the append queue follows the log stores, MongoDB first`() {
        val mongo = Unopened("m", BackendKind.MONGO)
        val postgres = Unopened("p", BackendKind.POSTGRES)
        val layout = StoreLayout("p", logs = mapOf(account to "m"))
        HostBackends.of(mapOf("m" to mongo, "p" to postgres), layout, 1).let {
            it.appendQueue.assert().isEqualTo(AppendQueueOptions.MONGO)
            it.kind.assert().isEqualTo(BackendKind.POSTGRES)
        }
        HostBackends.of(mapOf("p" to postgres), StoreLayout("p"), 1).appendQueue.assert()
            .isEqualTo(AppendQueueOptions.POSTGRES)
    }

    @Test
    fun `the stores of wow-stores are opened by their types`() {
        val beans = DefaultListableBeanFactory()
        val stores = StoreInstances.open(
            properties {
                stores["a"] = WowProperties.Store().apply { type = BackendKind.MEMORY }
                stores["b"] = WowProperties.Store().apply { type = BackendKind.MEMORY }
            },
            beans,
            javaClass.classLoader,
        )
        stores.mapValues { it.value.kind }.assert().isEqualTo(
            mapOf("a" to BackendKind.MEMORY, "b" to BackendKind.MEMORY)
        )
        StoreInstances.open(properties(), beans, javaClass.classLoader).keys.assert()
            .containsExactly(ContextBackends.DEFAULT_STORE)
    }

    @Test
    fun `a store that cannot be opened is refused`() {
        val beans = DefaultListableBeanFactory()
        fun open(block: WowProperties.() -> Unit) = assertThrows<IllegalStateException> {
            StoreInstances.open(properties(block), beans, javaClass.classLoader)
        }.message!!
        open { stores["a"] = WowProperties.Store() }.assert().contains("wow.stores.a.type is not set")
        open { stores["a"] = WowProperties.Store().apply { type = BackendKind.AUTO } }.assert().contains("is auto")
        open {
            backend = BackendKind.MEMORY
            stores["a"] = WowProperties.Store().apply { type = BackendKind.MEMORY }
        }.assert().contains("wow.backend is MEMORY")
        open { stores["a"] = WowProperties.Store().apply { type = BackendKind.POSTGRES } }.assert()
            .contains("no DataSource bean")
        open { stores["a"] = WowProperties.Store().apply { type = BackendKind.MONGO } }.assert()
            .contains("no MongoClient bean")
        open {
            stores["a"] = WowProperties.Store().apply {
                type = BackendKind.POSTGRES
                dataSource = "missing"
            }
        }.assert().contains("names missing, which is no DataSource bean")
    }

    @Test
    fun `a named bean backs its store`() {
        val beans = DefaultListableBeanFactory()
        beans.registerBeanDefinition("first", RootBeanDefinition(Unpooled::class.java))
        beans.registerBeanDefinition("second", RootBeanDefinition(Unpooled::class.java))
        val stores = StoreInstances.open(
            properties {
                stores["a"] = WowProperties.Store().apply {
                    type = BackendKind.POSTGRES
                    dataSource = "second"
                }
            },
            beans,
            javaClass.classLoader,
        )
        (stores.getValue("a") as PostgresStore).dataSource.assert().isSameAs(beans.getBean("second"))
        stores.getValue("a").toString().assert().isEqualTo("a (POSTGRES)")
    }

    @Test
    fun `a host serves an aggregate whose log is on a store of its own`() {
        val host = startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.accounts.type" to "memory",
            "wow.log.store" to "main",
            "wow.aggregates.[bank.account].log.store" to "accounts",
            "wow.aggregates.[bank.account].snapshot.store" to "accounts",
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
    private class Unopened(
        name: String,
        kind: BackendKind,
        override val location: Any = Any()
    ) : HostStore(name, kind) {
        override fun openCheckpoints(): CheckpointStore = error("unused")

        override fun openLeases(): LeaseStore = error("unused")

        override fun openLedger(): FailureLedger = error("unused")

        override fun openReservations(): ReservationStore = error("unused")

        override fun openLog(context: String, partitions: Int): EventLog = error("unused")

        override fun openSnapshots(context: String): SnapshotStore = InMemorySnapshotStore()

        override fun openTimers(context: String): TimerStore = error("unused")
    }

    /** A `DataSource` bean that is never connected. */
    class Unpooled : DataSource by (
        java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, _, _ -> error("unused") } as DataSource
        )
}
