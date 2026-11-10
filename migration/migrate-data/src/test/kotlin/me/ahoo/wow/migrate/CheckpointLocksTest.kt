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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

/** One run of each role on a checkpoint at a time: the file's locks, the target's leases and their fence. */
class CheckpointLocksTest {
    @TempDir
    lateinit var directory: Path

    private val tail = CheckpointHolder("tail", "migrate-tail-0", 1, "run-a")
    private val import = CheckpointHolder("import", "migrate-import-0", 1, "run-b")

    @Test
    fun `a file checkpoint's role should be held by one run, naming it to the next, and freed when it ends`(): Unit =
        runBlocking {
            val file = directory.resolve("migrate-data.checkpoint.json")
            FileCheckpointLocks(file, tail).holding(setOf(CheckpointRole.IMPORT)) {
                val refused = assertThrows<MigrationException> {
                    runBlocking { FileCheckpointLocks(file, import).holding(setOf(CheckpointRole.IMPORT)) { } }
                }
                refused.message.assert().startsWith(
                    "The checkpoint $file is in use: tail on migrate-tail-0 (pid 1, run run-a), since ",
                ).contains(
                    "holds its import lock ($file.import.lock). One import, import --final, tail or export runs on a checkpoint at a time"
                )
                // Another role shares the checkpoint: a comparison beside the tail.
                FileCheckpointLocks(file, import).holding(setOf(CheckpointRole.COMPARE)) { "compared" }
                    .assert().isEqualTo("compared")
            }
            FileCheckpointLocks(
                file,
                import
            ).holding(setOf(CheckpointRole.IMPORT, CheckpointRole.EXPORT)) { "exported" }
                .assert().isEqualTo("exported")
            Files.readString(directory.resolve("migrate-data.checkpoint.json.export.lock"))
                .assert().startsWith("import on migrate-import-0 (pid 1, run run-b), since ")
        }

    @Test
    fun `a run should fail, naming the holder, while the holder renews its lease`() = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        val holding = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        launch {
            leases(store, tail).holding(setOf(CheckpointRole.IMPORT)) {
                holding.complete(Unit)
                done.await()
            }
        }
        holding.await()
        val refused = assertThrows<MigrationException> {
            leases(store, import).holding(setOf(CheckpointRole.IMPORT)) { }
        }
        done.complete(Unit)
        refused.message.assert().startsWith(
            "The checkpoint target:order-service is in use: tail on migrate-tail-0 (pid 1, run run-a) holds its " +
                "import lease, renewed while it runs (now until ",
        ).endsWith(
            "One import, import --final, tail or export runs on a checkpoint at a time: stop that run, or wait for it to end."
        )
        // It saw the renewal within one round, not a whole TTL.
        (testScheduler.currentTime < TargetCheckpointLeases.TTL.inWholeMilliseconds).assert().isTrue()
    }

    @Test
    fun `a run that cannot take every role should give back the ones it took`() = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        val holding = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        launch {
            leases(store, tail).holding(setOf(CheckpointRole.EXPORT)) {
                holding.complete(Unit)
                done.await()
            }
        }
        holding.await()
        assertThrows<MigrationException> {
            leases(store, import).holding(setOf(CheckpointRole.IMPORT, CheckpointRole.EXPORT)) { }
        }.message.assert().contains("holds its export lease")
        store.owners("${TargetCheckpointLeases.GROUP_PREFIX}order-service").map { it.partition }
            .assert().containsExactly(CheckpointRole.EXPORT.partition)
        done.complete(Unit)
    }

    @Test
    fun `a crashed holder's lease should be taken over once it expires, at the next epoch`() = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        // A run that stopped without releasing: nothing renews its lease.
        store.acquire(
            "${TargetCheckpointLeases.GROUP_PREFIX}order-service",
            0,
            tail.toString(),
            TargetCheckpointLeases.TTL
        )
        val reported = mutableListOf<String>()
        val epochs = leases(store, import, reported::add).holding(setOf(CheckpointRole.IMPORT)) { it.epochs }
        epochs.assert().isEqualTo(mapOf(CheckpointRole.IMPORT to 2L))
        (testScheduler.currentTime >= TargetCheckpointLeases.TTL.inWholeMilliseconds).assert().isTrue()
        reported.single().assert().startsWith(
            "Waiting for the import lease of the checkpoint target:order-service, which tail on migrate-tail-0 " +
                "(pid 1, run run-a) holds until ",
        )
        // Released when the run ended.
        store.owners("${TargetCheckpointLeases.GROUP_PREFIX}order-service").assert().isEmpty()
    }

    @Test
    fun `a role taken first should be renewed while the run waits for a later one a crashed run held`() = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        val group = "${TargetCheckpointLeases.GROUP_PREFIX}order-service"
        // A snapshot run that crashed just now: its lease lasts one TTL more, renewed by nothing.
        store.acquire(group, CheckpointRole.SNAPSHOT.partition, tail.toString(), TargetCheckpointLeases.TTL)
        var enteredAt = -1L
        leases(store, import).holding(setOf(CheckpointRole.IMPORT, CheckpointRole.SNAPSHOT)) { lock ->
            enteredAt = testScheduler.currentTime
            lock.ensureHeld()
            lock.epochs.keys.assert().containsExactly(CheckpointRole.IMPORT, CheckpointRole.SNAPSHOT)
            // Both are renewed on: a lease past the first TTL from the start still holds.
            delay(TargetCheckpointLeases.TTL)
            lock.ensureHeld()
            store.owners(group).map { it.owner }.toSet().assert().containsExactly(import.toString())
        }
        (enteredAt >= TargetCheckpointLeases.TTL.inWholeMilliseconds).assert().isTrue()
        store.owners(group).assert().isEmpty()
    }

    @Test
    fun `a role lost while the run waits for a later one should stop the run before its block`() = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        val group = "${TargetCheckpointLeases.GROUP_PREFIX}order-service"
        store.acquire(group, CheckpointRole.SNAPSHOT.partition, tail.toString(), TargetCheckpointLeases.TTL)
        // The store forgets the import lease once it was taken: its renewal finds it gone.
        val forgetful = object : LeaseStore by store {
            override suspend fun renew(
                group: String,
                partitions: Collection<Int>,
                owner: String,
                ttl: kotlin.time.Duration,
            ) = emptyList<me.ahoo.wow.spi.subscription.Lease>()
        }
        var entered = false
        assertThrows<MigrationException> {
            leases(forgetful, import).holding(setOf(CheckpointRole.IMPORT, CheckpointRole.SNAPSHOT)) { entered = true }
        }.message.assert().startsWith("This run lost its import lease of the checkpoint target:order-service")
        entered.assert().isFalse()
        store.owners(group).assert().isEmpty()
    }

    @Test
    fun `a run should wait at most one lease for a holder that renews nothing, then fail naming it`() = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        // A holder of a longer lease than this run's, which it does not renew while this run waits.
        store.acquire("${TargetCheckpointLeases.GROUP_PREFIX}order-service", 0, tail.toString(), 300.seconds)
        assertThrows<MigrationException> { leases(store, import).holding(setOf(CheckpointRole.IMPORT)) { } }
            .message.assert().startsWith("The checkpoint target:order-service is in use: tail on migrate-tail-0")
        testScheduler.currentTime.assert()
            .isGreaterThanOrEqualTo((TargetCheckpointLeases.TTL + TargetCheckpointLeases.RENEWAL).inWholeMilliseconds)
    }

    @Test
    fun `a lease store that stops answering should end the run's lease at its expiry, and its release be reported`() =
        runTest {
            val store = InMemoryLeaseStore(virtualClock())
            val down = object : LeaseStore by store {
                override suspend fun renew(
                    group: String,
                    partitions: Collection<Int>,
                    owner: String,
                    ttl: kotlin.time.Duration,
                ): List<me.ahoo.wow.spi.subscription.Lease> = throw IllegalStateException("connection refused")

                override suspend fun release(group: String, partition: Int, owner: String): Boolean =
                    throw IllegalStateException("connection refused")
            }
            val reported = mutableListOf<String>()
            val lost = assertThrows<MigrationException> {
                leases(down, tail, reported::add).holding(setOf(CheckpointRole.IMPORT)) { lock ->
                    delay(TargetCheckpointLeases.RENEWAL + 1.seconds)
                    // Not renewed, but not expired yet: the run goes on.
                    lock.ensureHeld()
                    delay(TargetCheckpointLeases.TTL)
                    lock.ensureHeld()
                }
            }
            lost.message.assert().startsWith("This run lost its import lease of the checkpoint target:order-service")
            reported.assert().contains(
                "The lease store did not renew the leases of the checkpoint target:order-service: connection refused",
                "The lease store did not release the import lease of the checkpoint target:order-service: " +
                    "connection refused",
            )
        }

    @Test
    fun `a run whose renewal finds its lease gone should fail before it writes`() = runTest {
        val store = InMemoryLeaseStore(virtualClock())
        val forgetful = object : LeaseStore by store {
            override suspend fun renew(
                group: String,
                partitions: Collection<Int>,
                owner: String,
                ttl: kotlin.time.Duration
            ) =
                emptyList<me.ahoo.wow.spi.subscription.Lease>()
        }
        val lost = assertThrows<MigrationException> {
            leases(forgetful, tail).holding(setOf(CheckpointRole.IMPORT)) { lock ->
                delay(TargetCheckpointLeases.RENEWAL + 1.seconds)
                TargetCheckpointRepository(InMemoryCheckpointTable(), "order-service", lock).save(ImportCheckpoints())
            }
        }
        lost.message.assert().isEqualTo(
            "This run lost its import lease of the checkpoint target:order-service: it was not renewed in time, and " +
                "another run may hold it now. It stops without writing.",
        )
    }

    @Test
    fun `a run that paused past its lease should fail at its first write once another run wrote at a later epoch`() {
        val table = InMemoryCheckpointTable()
        val paused = TargetCheckpointRepository(table, "order-service", held(CheckpointRole.IMPORT to 1))
        val compare = TargetCheckpointRepository(table, "order-service", held(CheckpointRole.COMPARE to 1))
        paused.update { it.with(CART, AggregateCheckpoint(startedAt = 1)) }
        // It pauses; another run takes the import lease over, at epoch 2, and writes.
        TargetCheckpointRepository(table, "order-service", held(CheckpointRole.IMPORT to 2))
            .update { it.with(CART, AggregateCheckpoint(startedAt = 2)) }
        // A run of another role writes as before, keeping the import's fence.
        compare.update { it.with(CART, CompareCheckpoint(compared = 1)) }
        table.load("order-service")!!.fences.assert().isEqualTo(mapOf("import" to 2L, "compare" to 1L))

        val fenced = assertThrows<MigrationException> {
            paused.update { it.with(CART, AggregateCheckpoint(startedAt = 1, completed = true)) }
        }
        fenced.message.assert().isEqualTo(
            "This run lost its import lease of the checkpoint target:order-service to another run, which has written " +
                "the checkpoint since (lease epoch 2, this run's 1). It stops without writing.",
        )
        val stored = table.load("order-service")!!
        stored.checkpoints.of(CART)!!.startedAt.assert().isEqualTo(2)
        stored.checkpoints.comparisonOf(CART)!!.compared.assert().isEqualTo(1)
        stored.version.assert().isEqualTo(3)

        val keys = assertThrows<MigrationException> {
            runBlocking {
                TargetImportedKeys(
                    table,
                    "order-service",
                    held(CheckpointRole.IMPORT to 1)
                ).add(listOf(KEY))
            }
        }
        keys.message.assert().contains("(lease epoch 2, this run's 1)")
        runBlocking {
            val current = TargetImportedKeys(table, "order-service", held(CheckpointRole.IMPORT to 2))
            current.add(listOf(KEY))
            assertThrows<MigrationException> {
                runBlocking {
                    TargetImportedKeys(
                        table,
                        "order-service",
                        held(CheckpointRole.IMPORT to 1)
                    ).retain("order_no") { false }
                }
            }
            val kept = mutableListOf<ImportedKey>()
            current.forEach("order_no") { kept += it }
            kept.assert().containsExactly(KEY)
        }
    }

    @Test
    fun `an update should be retried over a write that came between, losing neither`() {
        val table = InMemoryCheckpointTable()
        val tailing = TargetCheckpointRepository(table, "order-service", held(CheckpointRole.IMPORT to 1))
        val comparing = TargetCheckpointRepository(table, "order-service", held(CheckpointRole.COMPARE to 1))
        var between = true
        table.beforeLoad = {
            if (between) {
                between = false
                comparing.update { it.with(CART, CompareCheckpoint(compared = 7)) }
            }
        }
        tailing.update { it.with(CART, AggregateCheckpoint(startedAt = 1, tailedTo = 9)) }
        val stored = tailing.load()
        stored.of(CART)!!.tailedTo.assert().isEqualTo(9)
        stored.comparisonOf(CART)!!.compared.assert().isEqualTo(7)
    }

    @Test
    fun `the imported keys kept in the target should read in pages, in the order recorded, and forget what is not kept`() =
        runTest {
            val keys = TargetImportedKeys(
                InMemoryCheckpointTable(),
                "order-service",
                held(CheckpointRole.IMPORT to 1),
                2
            )
            val recorded = (1..5).map { KEY.copy(key = "no-$it") }
            keys.add(recorded)
            keys.add(listOf(KEY.copy(name = "user_name", key = "alice")))
            keys.retain("order_no") { it.key != "no-2" && it.key != "no-5" }
            val read = mutableListOf<String>()
            keys.forEach("order_no") { read += it.key }
            read.assert().containsExactly("no-1", "no-3", "no-4")
            val others = mutableListOf<String>()
            keys.forEach("user_name") { others += it.key }
            others.assert().containsExactly("alice")
        }

    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    private fun TestScope.leases(
        store: LeaseStore,
        holder: CheckpointHolder,
        report: (String) -> Unit = {},
    ): TargetCheckpointLeases = TargetCheckpointLeases(
        store,
        "order-service",
        holder,
        clock = { testScheduler.currentTime },
        report = report,
        renewing = StandardTestDispatcher(testScheduler),
    )

    private fun held(vararg epochs: Pair<CheckpointRole, Long>): CheckpointLock = object : CheckpointLock {
        override val epochs: Map<CheckpointRole, Long> = mapOf(*epochs)
    }

    private companion object {
        val CART = NineXAggregate("example-service", "cart")
        val KEY = ImportedKey.of(
            "order_no",
            "no-1",
            ReservationScope.of(AggregateType("example", "order"), "(0)", "order.no"),
            AggregateType("example", "order").ref("(0)", "order-1"),
        )
    }
}
