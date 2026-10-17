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
    private val source = InMemoryNineXCompensationSource(NineXCompensationExample.executions().filter { it.id != "0VYC0mp1nA0006" })

    private suspend fun importStreams() {
        val events = InMemoryNineXEventSource(NineXExample.streams())
        val importer = EventStreamImporter(events, plan, { log }, checkpoints)
        importer.importAll(order)
        importer.importAll(cart)
    }

    private fun importer(options: ImportOptions = ImportOptions(pageSize = 2), from: NineXCompensationSource = source) =
        CompensationImporter(from, plan, { log }, { ledger }, checkpoints, options, { reports += it.render() }, { reports += it })

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
                    1,
                    "IllegalState: the order $ORDER_ID failed in CartSaga",
                    FAILED_AT,
                    retrySpec = ParkedRetrySpec.of(10, 180.seconds, 120.seconds),
                ),
            )
            // 2: renamed function, one retry made, the applied spec and the recoverable mark.
            ledger.parked(ORDER_CREATED, ref).assert().isEqualTo(
                ParkedAggregate.of(
                    ORDER_CREATED,
                    ref,
                    1,
                    "Conflict: stock reserved twice",
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
    fun `a dry run resolves every execution and parks none`(): Unit = runTest {
        importStreams()
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
        reports.assert().contains("9.x compensation compared: 3 unresolved executions, 0 differ, 1 not imported")

        ledger.resolve(CART_SAGA, ref)
        ledger.resolve(ORDER_CREATED, ref)
        ledger.park(ParkedAggregate.of(ORDER_CREATED, ref, 2, "later", FAILED_AT))
        val result = comparison().compare()
        result.assert().isEqualTo(CompensationComparisonResult(3, 2, 1))
        result.passed.assert().isFalse()
        reports.assert()
            .anyMatch { it.startsWith("DIFFERS 9.x execution 0VYC0mp1nA0001") && it.endsWith("9.x $CART_SAGA v1, V10 (none)") }
            .anyMatch { it.startsWith("DIFFERS 9.x execution 0VYC0mp1nA0002") && it.endsWith("V10 parked from v2") }
    }

    @Test
    fun `the reason is the error code alone without a message, and an unknown mark is unknown`() {
        val execution = NineXCompensationExample.execution(1).copy(errorMessage = " ")
        CompensationPlan.reasonOf(execution).assert().isEqualTo("IllegalState")
        CompensationPlan.recoverableOf("RECOVERABLE").assert().isEqualTo(Recoverable.RECOVERABLE)
        CompensationPlan.recoverableOf("SOMETIMES").assert().isEqualTo(Recoverable.UNKNOWN)
    }

    private companion object {
        /** When the example's executions failed. */
        const val FAILED_AT = 1_792_004_190_000L
    }
}
