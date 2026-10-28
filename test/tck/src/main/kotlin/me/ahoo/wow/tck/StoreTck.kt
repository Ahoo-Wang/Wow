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

package me.ahoo.wow.tck

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.tck.ledger.FailureLedgerSpec
import me.ahoo.wow.tck.log.EventLogSpec
import me.ahoo.wow.tck.reservation.ReservationStoreSpec
import me.ahoo.wow.tck.snapshot.SnapshotStoreSpec
import me.ahoo.wow.tck.subscription.CheckpointStoreSpec
import me.ahoo.wow.tck.subscription.LeaseStoreSpec
import me.ahoo.wow.tck.timer.TimerStoreSpec
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayNameGeneration
import org.junit.jupiter.api.DisplayNameGenerator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.util.ServiceLoader
import java.util.UUID

/**
 * A whole store's contract: the backend gives the [config] of a store, and the kit opens it through its
 * [StoreProvider], as the host does, and runs every port's spec against what the store opens. A port the provider
 * forgets to open, or opens wrong, fails here, where the per-port specs (which a backend's test feeds its own classes)
 * would not see it.
 *
 * - [EventLogSpec] when the store holds logs ([Store.holdsLogs]), its snapshot store beside it when it holds
 *   snapshots too;
 * - [SnapshotStoreSpec] when it holds snapshots ([Store.holdsSnapshots]);
 * - the control plane when it holds logs, its home: [TimerStoreSpec], [CheckpointStoreSpec], [LeaseStoreSpec],
 *   [FailureLedgerSpec] and [ReservationStoreSpec]. A store that holds no log must refuse each of those ports, and its
 *   log, with an error that says so ([UnsupportedOperationException]).
 *
 * A spec that does not apply to the store skips with the reason. Each test opens one store from [config], as the host
 * does, and creates its schema as its ports open; a store in memory keeps its data in that instance, so the test's
 * ports share it. The specs keep their tests apart by their own contexts, groups and names. Where a spec moves the
 * time, the store is opened with its clock ([StoreConfig.clock]).
 *
 * A backend extends it with its configuration, and keeps its per-port specs for what only they reach (a hook of its
 * own). The report names the backend's class and the port: `PostgresStoreTck > EventLog > ...` ([PortNames]).
 */
@DisplayNameGeneration(StoreTck.PortNames::class)
abstract class StoreTck {
    /**
     * The configuration of the store under test: its type names the provider ([provider]), and its options and
     * resources reach an empty storage the tests may share. Its [StoreConfig.clock] is replaced where a spec moves the
     * time.
     */
    protected abstract fun config(): StoreConfig

    /** The provider of the store's type, by default the one [ServiceLoader] finds on the class path. */
    protected open fun provider(type: String): StoreProvider =
        ServiceLoader.load(StoreProvider::class.java, javaClass.classLoader).firstOrNull { it.type == type }
            ?: error("No StoreProvider of type $type is on the class path (META-INF/services).")

    /** A stream [EventLogSpec.refused] changes so that the store refuses it, or null when it stores every stream. */
    protected open fun refused(stream: EventStreamRecord): EventStreamRecord? = null

    /**
     * The facets the store says it has ([Store.facet]): its query side (`me.ahoo.wow.query.store.StoreQueries`) when
     * the backend serves queries, its pool ([me.ahoo.wow.spi.store.StorePool]) when the pool says its connections.
     * None by default.
     */
    protected open val facets: List<Class<*>> = emptyList()

    /** A new store from [config], its time from [clock], as the host opens one. */
    protected fun openStore(clock: Clock = Clock.systemUTC()): Store {
        val config = config()
        val timed = StoreConfig.of(
            config.name,
            config.type,
            config.ddl,
            config.options,
            config.polling,
            config.service,
            config.resources,
            config.development,
            clock,
        )
        return provider(config.type).open(timed)
    }

    /** The store of the test, opened once. */
    private val store: Store by lazy { openStore() }

    /** [port] of [store], its schema created, once the store [holds] it; else the test is skipped, saying why. */
    private fun <T> port(store: Store, holds: (Store) -> Boolean, why: String, port: (Store) -> T): T {
        assumeTrue(holds(store)) { "Skipped: the ${store.type} store $why." }
        return port(store).also { store.createSchemas() }
    }

    private fun <T> logPort(store: Store = this.store, port: (Store) -> T): T =
        port(store, Store::holdsLogs, NO_LOG, port)

    /**
     * Skips the test unless the store [holds] the port, before the test runs: a spec may open its port inside an
     * assertion (`assertThrows`), where a skip would read as the wrong failure.
     */
    private fun assumeHolds(holds: (Store) -> Boolean, why: String) {
        assumeTrue(holds(store)) { "Skipped: the ${store.type} store $why." }
    }

    @Test
    fun `a store that holds no log should refuse the log and the control plane, saying so`() {
        assumeFalse(store.holdsLogs) { "Skipped: the ${store.type} store holds logs." }
        listOf<Pair<String, () -> Any>>(
            "log" to { store.log("tck", 1) },
            "timers" to { store.timers("tck", 1) },
            "checkpoints" to { store.checkpoints },
            "leases" to { store.leases },
            "ledger" to { store.ledger },
            "reservations" to { store.reservations },
        ).forEach { (port, open) ->
            assertThrows<UnsupportedOperationException>(port) { open() }.message.assert().describedAs(port)
                .contains(store.name)
        }
    }

    @Test
    fun `the store should name its provider's type`() {
        store.type.assert().isEqualTo(config().type)
    }

    @Test
    fun `the store should have each facet it says it has, of that type, and none it does not know`() {
        facets.forEach { type ->
            store.facet(type).assert().describedAs(type.name).isNotNull().isInstanceOf(type)
        }
        store.facet(UnknownFacet::class.java).assert().isNull()
    }

    /** A facet no store has. */
    private class UnknownFacet

    @Test
    fun `the store should answer its probe, and close once however often it is closed`(): Unit = runBlocking {
        store.probe()
        val closing = openStore()
        closing.close()
        closing.close()
    }

    @Nested
    inner class Log : EventLogSpec() {
        @BeforeEach
        fun holdsLogs() = assumeHolds(Store::holdsLogs, NO_LOG)

        override fun createLog(context: String, partitions: Int): EventLog =
            logPort { it.log(context, partitions) }

        /** The context of the last snapshots [createSnapshots] opened, whose reads with the log's tails the store offers. */
        private var snapshotsContext: String? = null

        override fun createSnapshots(context: String): SnapshotStore? =
            store.takeIf { it.holdsLogs && it.holdsSnapshots }?.let {
                snapshotsContext = context
                it.snapshots(context).also { _ -> it.createSchemas() }
            }

        override fun snapshotTailsOf(log: EventLog, snapshots: SnapshotStore): SnapshotTailReader? =
            snapshotsContext?.let { context -> store.openedLog(context)?.let { store.snapshotTails(context) } }

        override fun refused(stream: EventStreamRecord): EventStreamRecord? = this@StoreTck.refused(stream)
    }

    @Nested
    inner class Snapshots : SnapshotStoreSpec() {
        @BeforeEach
        fun holdsSnapshots() = assumeHolds(Store::holdsSnapshots, NO_SNAPSHOTS)

        override fun createStore(context: String): SnapshotStore =
            port(store, Store::holdsSnapshots, NO_SNAPSHOTS) { it.snapshots(context) }
    }

    @Nested
    inner class Timers : TimerStoreSpec() {
        @BeforeEach
        fun holdsLogs() = assumeHolds(Store::holdsLogs, NO_LOG)

        /** A context of its own: the due read takes every aggregate type of the context. */
        override val context: String = "tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)

        override fun createStore(): TimerStore = logPort { it.timers(context, logPartitions) }
    }

    @Nested
    inner class Checkpoints : CheckpointStoreSpec() {
        @BeforeEach
        fun holdsLogs() = assumeHolds(Store::holdsLogs, NO_LOG)

        override fun createStore(): CheckpointStore = logPort { it.checkpoints }
    }

    @Nested
    inner class Leases : LeaseStoreSpec() {
        @BeforeEach
        fun holdsLogs() = assumeHolds(Store::holdsLogs, NO_LOG)

        override fun createStore(clock: Clock): LeaseStore = logPort(openStore(clock)) { it.leases }
    }

    @Nested
    inner class Ledger : FailureLedgerSpec() {
        @BeforeEach
        fun holdsLogs() = assumeHolds(Store::holdsLogs, NO_LOG)

        override fun createLedger(): FailureLedger = logPort { it.ledger }
    }

    @Nested
    inner class Reservations : ReservationStoreSpec() {
        @BeforeEach
        fun holdsLogs() = assumeHolds(Store::holdsLogs, NO_LOG)

        override fun createStore(clock: Clock): ReservationStore = logPort(openStore(clock)) { it.reservations }
    }

    /** Names each port's tests after the backend's class and the port: `PostgresStoreTck > EventLog`. */
    class PortNames : DisplayNameGenerator.Standard() {
        override fun generateDisplayNameForNestedClass(enclosingInstanceTypes: List<Class<*>>, nestedClass: Class<*>) =
            "${enclosingInstanceTypes.last().simpleName} > ${PORTS[nestedClass.simpleName] ?: nestedClass.simpleName}"
    }

    private companion object {
        const val CONTEXT_SUFFIX = 12
        const val NO_LOG = "holds no log, so no control plane either"
        const val NO_SNAPSHOTS = "holds no snapshots"

        /** The port each nested contract runs, by the nested class's name. */
        val PORTS = mapOf(
            "Log" to "EventLog",
            "Snapshots" to "SnapshotStore",
            "Timers" to "TimerStore",
            "Checkpoints" to "CheckpointStore",
            "Leases" to "LeaseStore",
            "Ledger" to "FailureLedger",
            "Reservations" to "ReservationStore",
        )
    }
}
