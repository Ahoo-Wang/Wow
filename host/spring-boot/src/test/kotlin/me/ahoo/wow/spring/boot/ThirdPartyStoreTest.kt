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
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.store.EventQueries
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.query.store.StoreQueryProvider
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A community backend, as one outside Wow would write it (review R3.3-1): the SPI's ports over Wow's in-memory ones,
 * standing in for a database, its snapshots queryable through the in-memory reference query backend. It is found on
 * the classpath, as `acme`, by `META-INF/services` (the test resources), or given as a bean of another type.
 */
open class AcmeStoreProvider(override val type: String = "acme") : StoreProvider {
    override fun open(config: StoreConfig): Store = AcmeStore(config)
}

/** An `acme` store: durable for the node, as a database would be, and remembering the settings it was opened with. */
class AcmeStore(config: StoreConfig) : Store(config) {
    /** What its snapshot queries read: every snapshot its snapshot stores write. */
    val queries = InMemoryQueryStorage()

    override val location: Any get() = this

    override fun openCheckpoints(): CheckpointStore = InMemoryCheckpointStore()

    override fun openLeases(): LeaseStore = InMemoryLeaseStore()

    override fun openLedger(): FailureLedger = InMemoryFailureLedger()

    override fun openReservations(): ReservationStore = InMemoryReservationStore()

    override fun openLog(context: String, partitions: Int): EventLog = InMemoryEventLog(partitions)

    override fun openSnapshots(context: String): SnapshotStore = QueriedSnapshotStore(InMemorySnapshotStore(), queries)

    override fun openTimers(context: String): TimerStore = InMemoryTimerStore()
}

/** The queries of the `acme` stores, counting the bindings it gives. */
class AcmeQueryProvider(override val type: String = "acme") : StoreQueryProvider {
    override fun snapshots(store: Store, options: StoreQueryOptions): SnapshotQueries = object : SnapshotQueries {
        override fun binding(type: AggregateType): QueryBackendBinding<QueryBackend> =
            (store as AcmeStore).queries.create(type).also { bindings.incrementAndGet() }
    }

    override fun events(store: Store, options: StoreQueryOptions): EventQueries = EventQueries { type ->
        store.openedLog(type.context)?.let { InMemoryEventQueryStorage.of(it).create(type) }
            ?.also { bindings.incrementAndGet() }
    }

    companion object {
        val bindings = AtomicInteger()
    }
}

/** A provider of `vendor` stores given as beans, as an application would wire a backend of its own. */
@Configuration(proxyBeanMethods = false)
class VendorStores {
    @Bean
    fun vendorStoreProvider(): StoreProvider = AcmeStoreProvider("vendor")

    @Bean
    fun vendorQueryProvider(): StoreQueryProvider = AcmeQueryProvider("vendor")
}

/** A community backend plugged into the host through the store provider SPI (review R3.3-1). */
class ThirdPartyStoreTest {
    private val account = AggregateType(BANK, "account")

    @Test
    fun `a provider on the classpath backs the stores of its type, placed per aggregate`() {
        val host = startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.acme.type" to "acme",
            "wow.stores.acme.options.region" to "eu-1",
            "wow.stores.acme.ddl" to "none",
            "wow.log.store" to "main",
            "wow.aggregates.[bank.account].log.store" to "acme",
            "wow.aggregates.[bank.account].snapshot.stores" to "acme",
        )
        host.use {
            val backends = host.context.getBean(HostBackends::class.java)
            val acme = backends.stores.getValue("acme") as AcmeStore
            acme.config.options.assert().containsEntry("region", "eu-1")
            acme.config.ddl.name.assert().isEqualTo("NONE")
            acme.config.service.assert().isEqualTo("bank-service")
            backends.snapshotStoreOf(account).assert().isSameAs(acme)

            val id = host.post("/bank/account/open-account", """{"owner":"acme"}""").body!!["ref"]["id"].asString()
            val ref = account.ref(Caller.DEFAULT_TENANT, id)
            runBlocking {
                acme.openedLog(BANK)!!.read(ref).toList().assert().hasSize(1)
                backends.stores.getValue("main").openedLog(BANK)!!.read(ref).toList().assert().isEmpty()
            }
            eventually { acme.queries.records(account).isNotEmpty() }
            val snapshots = host.post("/bank/account/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"}}""")
            snapshots.status.assert().isEqualTo(200)
            snapshots.body!!.values().map { it["state"]["owner"].asString() }.assert().containsExactly("acme")
            val events = host.post(
                "/bank/account/events/query",
                """{"kind":"list","filter":{"op":"ID","value":"$id"}}""",
            )
            events.status.assert().isEqualTo(200)
            events.body!!.values().map { it["version"].asInt() }.assert().containsExactly(1)
            // The gateways are bound to the acme query provider's backends.
            AcmeQueryProvider.bindings.get().assert().isPositive()
        }
    }

    @Test
    fun `a provider bean backs the stores of its type`() {
        val host = startHost(
            "wow.stores.main.type" to "memory",
            "wow.stores.v.type" to "vendor",
            "wow.log.store" to "main",
            "wow.aggregates.[bank.account].log.store" to "v",
            sources = listOf(HostTestApp::class.java, VendorStores::class.java),
        )
        host.use {
            val vendor = host.context.getBean(HostBackends::class.java).stores.getValue("v")
            vendor.type.assert().isEqualTo("vendor")
            val id = host.post("/bank/account/open-account", """{"owner":"v"}""").body!!["ref"]["id"].asString()
            runBlocking {
                vendor.openedLog(BANK)!!.read(account.ref(Caller.DEFAULT_TENANT, id)).toList().assert().hasSize(1)
            }
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "The condition did not hold in time." }
            Thread.sleep(20)
        }
    }
}
