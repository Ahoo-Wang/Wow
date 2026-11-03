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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * The fixture ci.yml's `migrate-data image` job runs the built image against (`src/test/resources/image-smoke`, mounted
 * read-only at `/config`): its Manifest is the 9.x example's V10 port, and `check` passes on it over the one 9.x
 * cart stream the job writes into its MongoDB (`ninex-example/cart-stream-v1.json`), as here, so the fixture keeps up
 * with the tool.
 */
class ImageSmokeFixtureTest {
    private val fixture = Path.of("src/test/resources/image-smoke")

    @Test
    fun `the fixture's Manifest should be the 9x example's V10 port`() {
        readManifests(listOf(fixture.resolve("manifest.json").toString())).assert().isEqualTo(NineXExample.manifest)
    }

    @Test
    fun `check should pass on the fixture over one 9x cart stream`() {
        val source = InMemoryNineXEventSource(listOf(NineXExample.stream("cart-stream-v1.json")))
        val connections = object : MigrationConnections {
            override val source: NineXEventSource = source
            override val snapshots: NineXSnapshotSource = InMemoryNineXSnapshotSource(events = source)
            override val reservations: NineXReservationSource = InMemoryNineXReservationSource()

            override fun logOf(context: String): EventLog = InMemoryEventLog(4)

            override fun snapshotStoreOf(context: String): SnapshotStore = InMemorySnapshotStore()

            override fun reservationStore(): ReservationStore = InMemoryReservationStore()

            override fun checkpointStore(): CheckpointStore = InMemoryCheckpointStore()

            override fun close() = Unit
        }
        val output = ByteArrayOutputStream()
        MigrateData(PrintStream(output, true), { connections })
            .run(listOf("check", "--config", fixture.resolve("migrate-data.json").toString()))
            .assert().describedAs(output.toString()).isEqualTo(MigrateData.OK)
        output.toString().assert().contains("Mapping check passed.")
    }
}
