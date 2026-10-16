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
    )

    private fun comparison() = ReservationComparison(events, keys, plan, { store }, 2) { reports += it }

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
        reports.assert().contains("PrepareKey order_no compared: 2 confirmed keys, 0 differ, 2 without an owner")

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
