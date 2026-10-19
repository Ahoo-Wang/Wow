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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.hours

class ReservationImporterTest {
    private val order = NineXAggregate("example-service", "order")
    private val created = NineXExample.stream("order-stream-v1.json")
    private val orderId = created.aggregateId.id
    private val now = 1_800_000_000_000L
    private val events = InMemoryNineXEventSource(
        NineXExample.streams() + created.copy(id = "stream-b", aggregateId = NineXAggregateId("order-b", "tenant-b")),
    )
    private val keys = InMemoryNineXReservationSource(
        listOf(
            key("order_no", "NO-1", """{"orderId":"$orderId"}"""),
            key("order_no", "NO-2", """{"orderId":"order-b"}""", ttlAt = null),
            key("order_no", "NO-3", """{"orderId":"missing"}"""),
            key("order_no", "NO-4", """{"orderId":"$orderId"}""", ttlAt = now + 1),
            key("order_no", "NO-5", """{"orderId":"$orderId"}""", ttlAt = now - 1),
            key("order_no", "NO-6", """{"other":1}"""),
            key("order_code", "C-1", "\"$orderId\""),
        ),
    )
    private val plan = MigrationPlan(
        NineXExample.map.copy(
            aggregates = NineXExample.map.aggregates.map {
                if (it.source() != order) {
                    it
                } else {
                    it.copy(
                        reservations = listOf(
                            ReservationMapping("order_no", "order.no", ownerId = "orderId"),
                            ReservationMapping("order_code", "order.code", ReservationTenancy.SYSTEM),
                        ),
                    )
                }
            },
        ),
        NineXExample.manifest,
    )
    private val store = InMemoryReservationStore()
    private val checkpoints = InMemoryCheckpointRepository(
        ImportCheckpoints().with(order, AggregateCheckpoint(startedAt = 0, completed = true)),
    )
    private val reports = mutableListOf<String>()
    private val owner = NineXExample.orderModel.type.ref(created.aggregateId.tenantId, orderId)
    private val ownerB = NineXExample.orderModel.type.ref("tenant-b", "order-b")

    private fun key(name: String, key: String, value: String, ttlAt: Long? = NineXPreparedKey.TTL_FOREVER) =
        NineXPreparedKey(name, key, WowJson.mapper.readTree(value), ttlAt)

    private fun scope(tenantId: String, namespace: String = "order.no") =
        ReservationScope.of(NineXExample.CONTEXT, tenantId, namespace)

    private val imported = InMemoryImportedKeys()

    private fun importer(options: ImportOptions = ImportOptions(pageSize = 2)) = ReservationImporter(
        events,
        keys,
        plan,
        { store },
        checkpoints,
        options,
        { reports += it.render() },
        { reports += it },
        { now },
        imported,
    )

    private fun comparison() = ReservationComparison(events, keys, plan, { store }, 2, imported) { reports += it }

    @Test
    fun `confirmed keys are reserved for their owners in their tenants, pending and expired ones skipped`(): Unit =
        runTest {
            val progress = importer().importAll("order_no")
            progress.assert().isEqualTo(ReservationProgress("order_no", 2, 0, 1, 1, 2, true))
            store.find(scope("(0)"), "NO-1")!!.also {
                it.owner.assert().isEqualTo(owner)
                it.confirmed.assert().isTrue()
            }
            store.find(scope("tenant-b"), "NO-2")!!.owner.assert().isEqualTo(ownerB)
            store.find(scope("(0)"), "NO-4").assert().isNull()
            store.find(scope("(0)"), "NO-5").assert().isNull()
            reports.assert()
                .contains(
                    "UNRESOLVED PrepareKey order_no/NO-3: no 9.x stream of example-service.order has the id " +
                        "missing; the key is not imported",
                )
                .contains("UNRESOLVED PrepareKey order_no/NO-6: its value has no id at orderId; $NOT_IMPORTED")
                .contains(
                    "PrepareKey order_no reservations done: 6 keys, 2 imported, 0 already there, 1 pending and 1 " +
                        "expired skipped, 2 without an owner",
                )
            checkpoints.load().reservationsOf("order_no")!!.after.assert().isEqualTo("NO-6")

            // Complete: a run again does nothing; a restart finds every key already there.
            importer().importAll("order_no").imported.assert().isEqualTo(2)
            importer(ImportOptions(pageSize = 2, restart = true)).importAll("order_no")
                .assert().isEqualTo(ReservationProgress("order_no", 0, 2, 1, 1, 2, true))
        }

    @Test
    fun `a system mapping reserves across tenants, its owner still in its tenant`(): Unit = runTest {
        importer().importAll("order_code").imported.assert().isEqualTo(1)
        store.find(scope(ReservationScope.SYSTEM_TENANT, "order.code"), "C-1")!!.owner.assert().isEqualTo(owner)
        keys.add(key("order_code", "C-2", "{}"))
        importer(ImportOptions(restart = true)).importAll("order_code").already.assert().isEqualTo(1)
        reports.assert().contains("UNRESOLVED PrepareKey order_code/C-2: its value is not an id; $NOT_IMPORTED")
    }

    private companion object {
        const val NOT_IMPORTED = "the key is not imported"
    }

    @Test
    fun `a key another owner holds in V10 stops the import`(): Unit = runTest {
        store.confirm(scope("(0)"), "NO-1", ownerB).assert().isEqualTo(ConfirmOutcome.Confirmed)
        assertThrows<MigrationException> { importer().importAll("order_no") }.message!!.assert()
            .contains("order_no/NO-1").contains(owner.toString()).contains(ownerB.toString())
    }

    @Test
    fun `a dry run resolves every key and reserves none`(): Unit = runTest {
        importer(ImportOptions(dryRun = true)).importAll("order_no").imported.assert().isEqualTo(2)
        store.find(scope("(0)"), "NO-1").assert().isNull()
        // The dry run of the streams before it recorded no checkpoint.
        ReservationImporter(events, keys, plan, { store }, InMemoryCheckpointRepository(), ImportOptions(dryRun = true))
            .importAll("order_no").imported.assert().isEqualTo(2)
    }

    @Test
    fun `the owner's streams are imported first, and the PrepareKey must be mapped`(): Unit = runTest {
        val fresh = ReservationImporter(events, keys, plan, { store }, InMemoryCheckpointRepository())
        assertThrows<MigrationException> { fresh.importAll("order_no") }.message!!.assert()
            .contains("The full import of example-service.order has not completed")
        assertThrows<MigrationException> { importer().importAll("user_name") }.message!!.assert()
            .contains("does not map the 9.x PrepareKey user_name")
        assertThrows<MigrationException> { comparison().compare("user_name") }
    }

    @Test
    fun `compare finds every confirmed key reserved for its owner, and reports one that is not`(): Unit = runTest {
        importer().importAll("order_no")
        comparison().compare("order_no").assert().isEqualTo(ReservationComparisonResult("order_no", 2, 0, 2))
        reports.assert().contains(
            "PrepareKey order_no compared: 2 confirmed keys, 0 differ, 2 without an owner; 0 imported keys no longer " +
                "confirmed to their owner in 9.x",
        )

        store.release(scope("(0)"), "NO-1", owner)
        store.reserve(scope("(0)"), "NO-1", owner, "hold", 1.hours)
        store.release(scope("tenant-b"), "NO-2", ownerB)
        val result = comparison().compare("order_no")
        result.differ.assert().isEqualTo(2)
        result.passed.assert().isFalse()
        reports.assert()
            .contains("DIFFERS PrepareKey order_no/NO-1: 9.x $owner, V10 $owner pending")
            .contains("DIFFERS PrepareKey order_no/NO-2: 9.x $ownerB, V10 (none)")
        store.confirm(scope("tenant-b"), "NO-2", owner)
        comparison().compare("order_no")
        reports.assert().contains("DIFFERS PrepareKey order_no/NO-2: 9.x $ownerB, V10 $owner")
    }

    @Test
    fun `a key 9x released after the import is released by the restart, and compare reports it`(): Unit = runTest {
        importer().importAll("order_no").imported.assert().isEqualTo(2)
        imported.all.map { it.key }.assert().containsExactly("NO-1", "NO-2")

        // 9.x rolls NO-1 back during the migration window (a rename): compare finds V10 still holding it.
        keys.remove("order_no", "NO-1")
        val stale = comparison().compare("order_no")
        stale.stale.assert().isEqualTo(1)
        stale.passed.assert().isFalse()
        reports.assert().contains("DIFFERS PrepareKey order_no/NO-1: V10 $owner, which 9.x no longer confirms it to")

        // A resumed run does not converge; the final restart releases it and forgets it.
        importer(ImportOptions(pageSize = 2, restart = true)).importAll("order_no")
            .assert().isEqualTo(ReservationProgress("order_no", 0, 1, 1, 1, 2, true, released = 1))
        store.find(scope("(0)"), "NO-1").assert().isNull()
        store.find(scope("tenant-b"), "NO-2")!!.owner.assert().isEqualTo(ownerB)
        imported.all.map { it.key }.assert().containsExactly("NO-2")
        reports.assert().contains("RELEASED PrepareKey order_no/NO-1 of $owner: 9.x no longer confirms it to them")
        comparison().compare("order_no").passed.assert().isTrue()
    }

    @Test
    fun `a key 9x gave to another owner moves to it on the final restart instead of stopping the import`(): Unit =
        runTest {
            importer().importAll("order_code").imported.assert().isEqualTo(1)
            val system = scope(ReservationScope.SYSTEM_TENANT, "order.code")
            store.find(system, "C-1")!!.owner.assert().isEqualTo(owner)

            // 9.x reprepares C-1 for order-b.
            keys.update(key("order_code", "C-1", "\"order-b\""))
            comparison().compare("order_code").also {
                it.differ.assert().isEqualTo(1)
                it.stale.assert().isEqualTo(1)
            }
            importer(ImportOptions(restart = true)).importAll("order_code")
                .assert().isEqualTo(ReservationProgress("order_code", 1, 0, 0, 0, 0, true, released = 1))
            store.find(system, "C-1")!!.also {
                it.owner.assert().isEqualTo(ownerB)
                it.confirmed.assert().isTrue()
            }
            imported.all.map { it.owner() }.assert().containsExactly(ownerB)
            comparison().compare("order_code").passed.assert().isTrue()
        }

    @Test
    fun `keys V10 holds that the import did not confirm are never released`(): Unit = runTest {
        // V10 already holds NO-1 for its owner, and holds NO-9 of its own, which 9.x never had.
        store.confirm(scope("(0)"), "NO-1", owner)
        store.confirm(scope("(0)"), "NO-9", owner)
        importer().importAll("order_no").assert().isEqualTo(ReservationProgress("order_no", 1, 1, 1, 1, 2, true))
        imported.all.map { it.key }.assert().containsExactly("NO-2")

        keys.remove("order_no", "NO-1")
        importer(ImportOptions(pageSize = 2, restart = true)).importAll("order_no").released.assert().isEqualTo(0)
        store.find(scope("(0)"), "NO-1")!!.owner.assert().isEqualTo(owner)
        store.find(scope("(0)"), "NO-9")!!.owner.assert().isEqualTo(owner)
        comparison().compare("order_no").stale.assert().isEqualTo(0)
    }

    @Test
    fun `a recorded key V10 no longer holds for its owner is forgotten, not released`(): Unit = runTest {
        importer().importAll("order_no")
        // Released in V10 (an operator), then taken by another owner.
        store.release(scope("(0)"), "NO-1", owner)
        store.confirm(scope("(0)"), "NO-1", ownerB)
        keys.remove("order_no", "NO-1")
        importer(ImportOptions(pageSize = 2, restart = true)).importAll("order_no").released.assert().isEqualTo(0)
        store.find(scope("(0)"), "NO-1")!!.owner.assert().isEqualTo(ownerB)
        imported.all.map { it.key }.assert().containsExactly("NO-2")
    }

    @Test
    fun `a 9x key is confirmed when prepared forever, and names its owner by its value`() {
        val forever = key("n", "k", """{"owner":{"id":"o-1","n":2}}""")
        forever.confirmed.assert().isTrue()
        forever.expired(Long.MAX_VALUE).assert().isFalse()
        forever.ownerId("owner.id").assert().isEqualTo("o-1")
        forever.ownerId("owner.n").assert().isNull()
        forever.ownerId("owner.id.x").assert().isNull()
        forever.ownerId(null).assert().isNull()
        key("n", "k", "\" \"").ownerId(null).assert().isNull()
        key("n", "k", "\"o\"", ttlAt = null).confirmed.assert().isTrue()
        key("n", "k", "\"o\"", ttlAt = 5).also {
            it.confirmed.assert().isFalse()
            it.expired(5).assert().isTrue()
            it.expired(4).assert().isFalse()
            it.toString().assert().isEqualTo("n/k")
        }
    }
}
