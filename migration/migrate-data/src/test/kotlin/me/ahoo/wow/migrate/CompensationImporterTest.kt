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
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.migrate.example.NineXCompensationExample
import me.ahoo.wow.migrate.example.NineXCompensationExample.CART_SAGA
import me.ahoo.wow.migrate.example.NineXCompensationExample.ORDER_CREATED
import me.ahoo.wow.migrate.example.NineXCompensationExample.ORDER_ID
import me.ahoo.wow.migrate.example.NineXCompensationExample.ORDER_STATE
import me.ahoo.wow.migrate.example.NineXCompensationExample.TENANT
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.seconds

class CompensationImporterTest {
    private val order = NineXAggregate("example-service", "order")
    private val cart = NineXAggregate("example-service", "cart")
    private val log = InMemoryEventLog(4)
    private val ledger = InMemoryFailureLedger()
    private val checkpoints = InMemoryCheckpointRepository()
    private val plan = NineXCompensationExample.plan
    private val ref = NineXExample.orderModel.type.ref(TENANT, ORDER_ID)
    private val reports = mutableListOf<String>()

    /** Every example execution but the projector's, which the port reads from another context's topic. */
    private val source =
        InMemoryNineXCompensationSource(NineXCompensationExample.executions().filter { it.id != "0VYC0mp1nA0006" })

    private suspend fun importStreams() {
        val events = InMemoryNineXEventSource(NineXExample.streams())
        val importer = EventStreamImporter(events, plan, { log }, checkpoints)
        importer.importAll(order)
        importer.importAll(cart)
    }

    private fun importer(options: ImportOptions = ImportOptions(pageSize = 2), from: NineXCompensationSource = source) =
        CompensationImporter(from, plan, {
            log
        }, { ledger }, checkpoints, options, { reports += it.render() }, { reports += it })

    private fun comparison() = CompensationComparison(source, plan, { log }, { ledger }, 2) { reports += it }

    @Test
    fun `unresolved executions park their aggregates for the mapped subscriptions, with their retry state`(): Unit =
        runTest {
            importStreams()
            importer().importAll().assert().isEqualTo(CompensationProgress(3, 0, 1, 0, true))

            // 1: no retry yet, the default spec; the 9.x error code and message are the reason, without the stack trace.
            ledger.parked(CART_SAGA, ref).assert().isEqualTo(
                ParkedAggregate.of(
                    CART_SAGA,
                    ref,
                    Partitioner.partitionOf(ref, log.partitions),
                    1,
                    "IllegalState: the order $ORDER_ID failed in CartSaga [9.x execution 0VYC0mp1nA0001]",
                    FAILED_AT,
                    retrySpec = ParkedRetrySpec.of(10, 180.seconds, 120.seconds),
                ),
            )
            // 2: renamed function, one retry made, the applied spec and the recoverable mark.
            ledger.parked(ORDER_CREATED, ref).assert().isEqualTo(
                ParkedAggregate.of(
                    ORDER_CREATED,
                    ref,
                    Partitioner.partitionOf(ref, log.partitions),
                    1,
                    "Conflict: stock reserved twice [9.x execution 0VYC0mp1nA0002]",
                    FAILED_AT + 181_000,
                    retries = 1,
                    retryAt = FAILED_AT + 180_000,
                    retrySpec = ParkedRetrySpec.of(5, 60.seconds, 30.seconds),
                    recoverable = Recoverable.RECOVERABLE,
                ),
            )
            // 4: a retry in flight (PREPARED) of an unrecoverable execution of a state event.
            ledger.parked(ORDER_STATE, ref)!!.also {
                it.fromVersion.assert().isEqualTo(3)
                it.retries.assert().isEqualTo(1)
                it.retryAt.assert().isEqualTo(FAILED_AT + 180_000)
                it.recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)
                it.reason.assert().doesNotContain("\tat ")
            }
            // 3 (succeeded) and 5 (deleted) are history; 7's order has no stream.
            ledger.list(CART_SAGA).assert().hasSize(1)
            reports.assert()
                .contains(
                    "UNRESOLVED 9.x execution 0VYC0mp1nA0007 (example-service.CartSaga.onOrderCreated on " +
                        "example-service.order/(0)/0VYMissingOrder v1): the V10 log holds no version 1 of " +
                        "${NineXExample.orderModel.type.ref(TENANT, "0VYMissingOrder")}; it is not imported",
                )
                .contains(
                    "9.x compensation done: 4 unresolved executions, 3 imported, 0 already parked, 1 not imported; 0 " +
                        "of other services left",
                )
            checkpoints.load().compensation!!.after.assert().isEqualTo("0VYC0mp1nA0007")

            // Complete: a run again does nothing; a restart finds every entry already parked.
            importer().importAll().imported.assert().isEqualTo(3)
            importer(ImportOptions(pageSize = 2, restart = true)).importAll()
                .assert().isEqualTo(CompensationProgress(0, 3, 1, 0, true))
        }

    @Test
    fun `an aggregate parked from an earlier version stays so, a later failure is covered by it`(): Unit = runTest {
        importStreams()
        val first = NineXCompensationExample.execution(1)
        val later = InMemoryNineXCompensationSource(
            listOf(first.copy(id = "a", version = 3), first.copy(id = "b"), first.copy(id = "c", version = 2)),
        )
        importer(from = later).importAll().assert().isEqualTo(CompensationProgress(2, 1, 0, 0, true))
        ledger.parked(CART_SAGA, ref)!!.fromVersion.assert().isEqualTo(1)
    }

    @Test
    fun `executions without a mapping, of an aggregate not imported or of another service are not imported`(): Unit =
        runTest {
            importStreams()
            val first = NineXCompensationExample.execution(1)
            val others = InMemoryNineXCompensationSource(
                listOf(
                    NineXCompensationExample.execution(6),
                    first.copy(id = "unmapped", function = first.function.copy(processor = "PaymentSaga")),
                    first.copy(id = "payment", aggregate = NineXAggregate("example-service", "payment")),
                    first.copy(id = "other", function = first.function.copy(context = "payment-service")),
                ),
            )
            importer(from = others).importAll().assert().isEqualTo(CompensationProgress(0, 0, 3, 1, true))
            reports.assert()
                .contains(
                    "UNRESOLVED 9.x execution 0VYC0mp1nA0006 (example-service.OrderProjector.onOrderPaid on " +
                        "example-service.order/(0)/$ORDER_ID v3): the V10 subscription OrderProjector.onOrderPaid of " +
                        "reporting does not read the log of example; it is not imported",
                )
                .anyMatch { it.contains("the 9.x processor example-service.PaymentSaga has no subscription mapping") }
                .anyMatch { it.contains("this map does not import the 9.x aggregate example-service.payment") }
            ledger.list(CART_SAGA).assert().isEmpty()
        }

    @Test
    fun `a dry run resolves every execution and parks none, after a dry run of the streams`(): Unit = runTest {
        importer(ImportOptions(dryRun = true)).importAll().assert().isEqualTo(CompensationProgress(4, 0, 0, 0, true))
        ledger.list(CART_SAGA).assert().isEmpty()
    }

    @Test
    fun `the event streams are imported first`(): Unit = runTest {
        assertThrows<MigrationException> { importer().importAll() }.message!!.assert()
            .contains("The full import of [example-service.order, example-service.cart] has not completed")
    }

    @Test
    fun `compare finds every unresolved execution parked, and reports one that is not`(): Unit = runTest {
        importStreams()
        importer().importAll()
        comparison().compare().assert().isEqualTo(CompensationComparisonResult(3, 0, 1))
        reports.assert().contains(
            "9.x compensation compared: 3 unresolved executions, 0 differ, 1 not imported; 0 imported entries no " +
                "longer unresolved in 9.x",
        )

        ledger.resolve(CART_SAGA, ref)
        ledger.resolve(ORDER_CREATED, ref)
        ledger.park(ParkedAggregate.of(ORDER_CREATED, ref, 0, 2, "later", FAILED_AT))
        val result = comparison().compare()
        result.assert().isEqualTo(CompensationComparisonResult(3, 2, 1))
        result.passed.assert().isFalse()
        reports.assert()
            .anyMatch { it.startsWith("DIFFERS 9.x execution 0VYC0mp1nA0001") && it.endsWith("9.x $CART_SAGA v1, V10 (none)") }
            .anyMatch { it.startsWith("DIFFERS 9.x execution 0VYC0mp1nA0002") && it.endsWith("V10 parked from v2") }
    }

    @Test
    fun `an execution 9x resolves after the import is resolved in V10 by the final restart`(): Unit = runTest {
        importStreams()
        importer().importAll().imported.assert().isEqualTo(3)

        // 9.x retries execution 1 successfully and skips execution 2 (deletes its aggregate) during the window.
        source.update(NineXCompensationExample.execution(1).copy(status = NineXExecutionFailed.SUCCEEDED))
        source.update(NineXCompensationExample.execution(2).copy(deleted = true))
        // A run on a complete checkpoint does not walk again, so it does not converge either.
        importer().importAll().resolved.assert().isEqualTo(0)
        ledger.isParked(CART_SAGA, ref).assert().isTrue()

        importer(ImportOptions(pageSize = 2, restart = true)).importAll()
            .assert().isEqualTo(CompensationProgress(0, 1, 1, 0, true, resolved = 2))
        ledger.parked(CART_SAGA, ref).assert().isNull()
        ledger.parked(ORDER_CREATED, ref).assert().isNull()
        ledger.parked(ORDER_STATE, ref).assert().isNotNull()
        reports.assert()
            .anyMatch { it.startsWith("RESOLVED $CART_SAGA $ref from v1: the 9.x execution 0VYC0mp1nA0001") }
            .anyMatch { it.endsWith("succeeded in 9.x") }
            .anyMatch { it.endsWith("skipped in 9.x") }
            .anyMatch { it.endsWith("2 imported entries resolved, 9.x no longer leaving them unresolved") }
        comparison().compare().passed.assert().isTrue()
    }

    @Test
    fun `the restart parks an aggregate again from the execution that still holds it`(): Unit = runTest {
        importStreams()
        val first = NineXCompensationExample.execution(1)
        val executions = InMemoryNineXCompensationSource(
            listOf(first.copy(id = "a"), first.copy(id = "b", version = 3)),
        )
        importer(from = executions).importAll().assert().isEqualTo(CompensationProgress(1, 1, 0, 0, true))
        CompensationPlan.originOf(ledger.parked(CART_SAGA, ref)!!.reason).assert().isEqualTo("a")

        // a succeeds; b, a later failure of the same aggregate, still waits in 9.x.
        executions.update(first.copy(id = "a", status = NineXExecutionFailed.SUCCEEDED))
        importer(ImportOptions(pageSize = 2, restart = true), executions).importAll()
            .assert().isEqualTo(CompensationProgress(1, 0, 0, 0, true, resolved = 1))
        ledger.parked(CART_SAGA, ref)!!.also {
            it.fromVersion.assert().isEqualTo(3)
            CompensationPlan.originOf(it.reason).assert().isEqualTo("b")
        }

        // Gone from 9.x altogether: resolved too.
        val empty = InMemoryNineXCompensationSource()
        importer(ImportOptions(restart = true), empty).importAll().resolved.assert().isEqualTo(1)
        ledger.list(CART_SAGA).assert().isEmpty()
        reports.assert().anyMatch { it.endsWith("the 9.x execution b it was imported for is gone from 9.x") }
    }

    @Test
    fun `entries V10 parked itself, or re-parked, are never touched`(): Unit = runTest {
        importStreams()
        importer().importAll()
        val other = NineXExample.orderModel.type.ref(TENANT, "another")
        ledger.park(ParkedAggregate.of(CART_SAGA, other, 0, 1, "IllegalState: boom", FAILED_AT))
        // A resume that failed again re-parked the imported entry with its own reason.
        ledger.repark(ParkedAggregate.of(ORDER_CREATED, ref, 0, 1, "Conflict: again", FAILED_AT))

        source.update(NineXCompensationExample.execution(2).copy(status = NineXExecutionFailed.SUCCEEDED))
        importer(ImportOptions(pageSize = 2, restart = true)).importAll().resolved.assert().isEqualTo(0)
        ledger.isParked(CART_SAGA, other).assert().isTrue()
        ledger.parked(ORDER_CREATED, ref)!!.reason.assert().isEqualTo("Conflict: again")
        comparison().compare().stale.assert().isEqualTo(0)
    }

    @Test
    fun `compare reports an imported entry whose execution 9x resolved since`(): Unit = runTest {
        importStreams()
        importer().importAll()
        source.update(NineXCompensationExample.execution(1).copy(status = NineXExecutionFailed.SUCCEEDED))
        val result = comparison().compare()
        result.assert().isEqualTo(CompensationComparisonResult(2, 0, 1, stale = 1))
        result.passed.assert().isFalse()
        reports.assert().anyMatch {
            it.startsWith("DIFFERS V10 $CART_SAGA $ref parked from v1: the 9.x execution 0VYC0mp1nA0001") &&
                it.endsWith("it was imported for is succeeded in 9.x")
        }
    }

    @Test
    fun `the origin of an imported entry is read back from its reason only`() {
        val execution = NineXCompensationExample.execution(2)
        CompensationPlan.originOf(CompensationPlan.reasonOf(execution)).assert().isEqualTo(execution.id)
        CompensationPlan.originOf("Conflict: stock reserved twice").assert().isNull()
        CompensationPlan.originOf("Conflict: [9.x execution ]").assert().isNull()
        CompensationPlan.originOf("Conflict: [9.x execution a b]").assert().isNull()
    }

    @Test
    fun `the reason is the error code alone without a message, and an unknown mark is unknown`() {
        val execution = NineXCompensationExample.execution(1).copy(errorMessage = " ")
        CompensationPlan.reasonOf(execution).assert().isEqualTo("IllegalState: [9.x execution 0VYC0mp1nA0001]")
        CompensationPlan.recoverableOf("RECOVERABLE").assert().isEqualTo(Recoverable.RECOVERABLE)
        CompensationPlan.recoverableOf("SOMETIMES").assert().isEqualTo(Recoverable.UNKNOWN)
    }

    private companion object {
        /** When the example's executions failed. */
        const val FAILED_AT = 1_792_004_190_000L
    }
}
