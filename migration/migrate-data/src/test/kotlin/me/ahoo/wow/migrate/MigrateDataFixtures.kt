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

import kotlinx.coroutines.flow.toList
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

internal abstract class MigrateDataFixtures {
    @TempDir
    lateinit var directory: Path

    protected val output = ByteArrayOutputStream()
    protected val source = InMemoryNineXEventSource(NineXExample.streams())
    protected val log = InMemoryEventLog(4)
    protected var closed = false
    protected var passes = 0

    protected var nineX: NineXEventSource = source
    protected var nineXSnapshots = InMemoryNineXSnapshotSource(NineXExample.snapshots(), source)
    protected val store = InMemorySnapshotStore()
    protected val prepared = InMemoryNineXReservationSource()
    protected val reservationStore = InMemoryReservationStore()
    protected val checkpointStore = InMemoryCheckpointStore()
    protected var nineXCompensation: NineXCompensationSource? = null
    protected val ledger = InMemoryFailureLedger()
    protected val exportTarget = InMemoryNineXEventTarget(source)
    protected val checkpointTable = InMemoryCheckpointTable()
    protected val leaseClock = MovableClock(System.currentTimeMillis())
    protected val leaseStore = InMemoryLeaseStore(leaseClock)

    protected val connections = object : MigrationConnections {
        override val source: NineXEventSource get() = nineX

        override val snapshots: NineXSnapshotSource get() = nineXSnapshots

        override fun logOf(context: String): EventLog = log

        override fun snapshotStoreOf(context: String): SnapshotStore = store

        override val reservations: NineXReservationSource get() = prepared

        override fun reservationStore(): ReservationStore = reservationStore

        override fun checkpointStore(): CheckpointStore = checkpointStore

        override val compensation: NineXCompensationSource? get() = nineXCompensation

        override fun failureLedger(): FailureLedger = ledger

        override val nineXTarget: NineXEventTarget get() = exportTarget

        override fun checkpointTable(): CheckpointTable = checkpointTable

        override fun leaseStore(): LeaseStore = leaseStore

        override fun close() {
            closed = true
        }
    }

    protected fun config(
        map: String = NineXExample.MAP_TEXT,
        createIndex: Boolean = false,
        checkpoint: String = "migrate-data.checkpoint.json",
        manifests: String = "\"manifests\": [\"manifest.json\"],",
    ): Path {
        Files.writeString(directory.resolve("migration-map.json"), map)
        Files.writeString(directory.resolve("manifest.json"), NineXExample.MANIFEST_TEXT)
        return directory.resolve("migrate-data.json").also {
            Files.writeString(
                it,
                """
                {
                  "map": "migration-map.json",
                  $manifests
                  "checkpoint": "$checkpoint",
                  "source": { "uri": "mongodb://localhost:27017", "database": "example_db",
                              "createCommitTimeIndex": $createIndex },
                  "target": { "type": "postgres", "jdbcUrl": "jdbc:postgresql://localhost:5432/wow" },
                  "partitions": { "example": 4 },
                  "pollIntervalMillis": 1
                }
                """.trimIndent(),
            )
        }
    }

    protected fun run(vararg args: String, running: () -> Boolean = { true }): Int =
        MigrateData(PrintStream(output, true), { connections }, running).run(args.toList())

    protected val printed: String get() = output.toString()
}
