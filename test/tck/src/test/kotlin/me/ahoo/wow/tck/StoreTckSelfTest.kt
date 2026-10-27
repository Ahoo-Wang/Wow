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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Opened
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The kit against a store that holds neither logs nor snapshots, as no backend does on its own: it refuses the log
 * and every control-plane port, saying so, and every port's spec skips with the reason.
 */
class StoreTckSelfTest : StoreTck() {
    /** A store of nothing: each port it is asked for is refused, naming the store. */
    private class EmptyStore(config: StoreConfig) : Store(config) {
        override val location: Any = "nowhere"
        override val holdsLogs: Boolean = false
        override val holdsSnapshots: Boolean = false

        private fun refused(): Nothing = throw UnsupportedOperationException("The store $name holds nothing.")

        override fun openCheckpoints(): Opened<CheckpointStore> = Opened.of(refused())

        override fun openLeases(): Opened<LeaseStore> = Opened.of(refused())

        override fun openLedger(): Opened<FailureLedger> = Opened.of(refused())

        override fun openReservations(): Opened<ReservationStore> = Opened.of(refused())

        override fun openLog(context: String, partitions: Int): Opened<EventLog> = Opened.of(refused())

        override fun openSnapshots(context: String): Opened<SnapshotStore> = Opened.of(refused())

        override fun openTimers(context: String, partitions: Int): Opened<TimerStore> = Opened.of(refused())
    }

    private val empty = object : StoreProvider {
        override val type: String = TYPE

        override fun open(config: StoreConfig): Store = EmptyStore(config)
    }

    override fun config(): StoreConfig = StoreConfig.of("nothing", TYPE)

    override fun provider(type: String): StoreProvider = empty

    /** The kit's own provider lookup, which a backend's subclass uses unless it overrides it. */
    private class Lookup : StoreTck() {
        override fun config(): StoreConfig = StoreConfig.of("lookup", "no-such-type")

        fun find(type: String): StoreProvider = provider(type)
    }

    @Test
    fun `a store type no provider on the class path has should fail, naming it`() {
        assertThrows<IllegalStateException> { Lookup().find("no-such-type") }.message.assert()
            .contains("no-such-type", "META-INF/services")
    }

    @Test
    fun `the report should name the backend and the port, or the nested class it does not know`() {
        val names = PortNames()
        names.generateDisplayNameForNestedClass(listOf(StoreTckSelfTest::class.java), Log::class.java)
            .assert().isEqualTo("StoreTckSelfTest > EventLog")
        names.generateDisplayNameForNestedClass(listOf(StoreTckSelfTest::class.java), Lookup::class.java)
            .assert().isEqualTo("StoreTckSelfTest > Lookup")
    }

    private companion object {
        const val TYPE = "tck-empty"
    }
}
