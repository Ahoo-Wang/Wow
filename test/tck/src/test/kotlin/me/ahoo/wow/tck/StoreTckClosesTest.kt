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
import java.util.concurrent.atomic.AtomicInteger

/**
 * `StoreTck` closes every store a test opened once the test ran (`closeStores`), as the host closes the stores it opens:
 * the options test too, which opens one with the provider directly (#7026). Against a real backend a store left open is
 * a client and its pool kept for the rest of the run.
 */
class StoreTckClosesTest {
    private class CountingStore(config: StoreConfig, private val closes: AtomicInteger) : Store(config) {
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

        override fun closeStore() {
            closes.incrementAndGet()
        }
    }

    @Test
    fun `every store the kit's options test opens should be closed once the test ran`() {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val counting = object : StoreProvider {
            override val type: String = "counting"

            override fun available(config: StoreConfig): Boolean = false

            override fun open(config: StoreConfig): Store {
                opens.incrementAndGet()
                return CountingStore(config, closes)
            }
        }
        val kit = object : StoreTck() {
            override fun config(): StoreConfig = StoreConfig.of("counting", "counting")

            override fun provider(type: String): StoreProvider = counting
        }

        kit.`the store should read only the options its provider declares, each declared once`()
        kit.closeStores()

        opens.get().assert().isEqualTo(1)
        closes.get().assert().describedAs("stores closed of the %s opened", opens.get()).isEqualTo(opens.get())
    }
}
