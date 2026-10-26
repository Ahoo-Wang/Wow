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

package me.ahoo.wow.spi.store

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.TailPacer
import me.ahoo.wow.spi.log.TailPolling
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The values of the store provider SPI (review R3.3-1): a store's configuration, resources and defaults. */
class StoreSpiTest {
    @Test
    fun `a configuration names its store and hands out its settings`() {
        val config = StoreConfig.of(
            "orders",
            "acme",
            ddl = StoreDdl.VALIDATE,
            options = mapOf("region" to "eu-1", "blank" to " "),
            service = "orders-service",
        )
        config.key.assert().isEqualTo("wow.stores.orders")
        config.option("region").assert().isEqualTo("eu-1")
        config.option("blank").assert().isNull()
        config.option("missing").assert().isNull()
        config.ddl.assert().isEqualTo(StoreDdl.VALIDATE)
        config.polling.assert().isEqualTo(TailPolling.DEFAULT)
        config.service.assert().isEqualTo("orders-service")
        config.resources.assert().isSameAs(StoreResources.NONE)
        config.toString().assert().contains("orders", "acme", "VALIDATE", "region")
        assertThrows<IllegalArgumentException> { StoreConfig.of(" ", "acme") }
        assertThrows<IllegalArgumentException> { StoreConfig.of("orders", "") }
    }

    @Test
    fun `resources are found by type, and by name when one is named`() {
        val resources = StoreResources.of(mapOf("first" to "a", "second" to "b", "count" to 1))
        resources.has(String::class.java).assert().isTrue()
        resources.has(Long::class.javaObjectType).assert().isFalse()
        resources.resource(Int::class.javaObjectType, null, "k").assert().isEqualTo(1)
        resources.resource(String::class.java, "second", "k").assert().isEqualTo("b")
        assertThrows<IllegalStateException> { resources.resource(String::class.java, null, "wow.stores.a.data-source") }
            .message.assert().contains("wow.stores.a.data-source is not set")
        assertThrows<IllegalStateException> { resources.resource(String::class.java, "count", "k") }
            .message.assert().contains("names count, which is no String")
        assertThrows<IllegalStateException> { StoreResources.NONE.resource(String::class.java, "x", "k") }
    }

    @Test
    fun `a provider and a store have defaults a backend may keep`() {
        val provider = object : StoreProvider {
            override val type: String = "acme"

            override fun open(config: StoreConfig): Store = Unopened(config)
        }
        provider.requirement.assert().isEqualTo("its resources")
        provider.available(StoreResources.NONE).assert().isFalse()
        val store = provider.open(StoreConfig.of("a", "acme"))
        store.name.assert().isEqualTo("a")
        store.type.assert().isEqualTo("acme")
        store.durable.assert().isTrue()
        store.holdsLogs.assert().isTrue()
        store.holdsSnapshots.assert().isTrue()
        store.openedLog("ctx").assert().isNull()
        store.toString().assert().isEqualTo("a (acme)")
    }

    @Test
    fun `tail polling is a value, and paces the tails of a log`() {
        val polling = TailPolling(20.milliseconds, 2.seconds)
        polling.assert().isEqualTo(TailPolling(20.milliseconds, 2.seconds)).isNotEqualTo(TailPolling.DEFAULT)
        polling.hashCode().assert().isEqualTo(TailPolling(20.milliseconds, 2.seconds).hashCode())
        polling.toString().assert().isEqualTo("TailPolling(interval=20ms, maxInterval=2s)")
        polling.pacer(4).interval.assert().isEqualTo(20.milliseconds)
        polling.pacer(4).maxInterval.assert().isEqualTo(2.seconds)
        assertThrows<IllegalArgumentException> { TailPolling(1.seconds, 1.milliseconds) }
        val pace = TailPacer.Pace(1.seconds, woken = true)
        pace.assert().isEqualTo(TailPacer.Pace(1.seconds, woken = true)).isNotEqualTo(TailPacer.Pace(1.seconds, false))
        pace.hashCode().assert().isEqualTo(TailPacer.Pace(1.seconds, woken = true).hashCode())
        pace.toString().assert().isEqualTo("Pace(idle=1s, woken=true)")
    }

    private class Unopened(config: StoreConfig) : Store(config) {
        override val location: Any = Any()

        override fun openCheckpoints(): CheckpointStore = error("unused")

        override fun openLeases(): LeaseStore = error("unused")

        override fun openLedger(): FailureLedger = error("unused")

        override fun openReservations(): ReservationStore = error("unused")

        override fun openLog(context: String, partitions: Int): EventLog = error("unused")

        override fun openSnapshots(context: String): SnapshotStore = error("unused")

        override fun openTimers(context: String, partitions: Int): TimerStore = error("unused")
    }
}
