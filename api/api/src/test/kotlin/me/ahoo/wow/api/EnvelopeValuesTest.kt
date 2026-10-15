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

package me.ahoo.wow.api

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.ledger.LedgerAggregate
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerResolution
import me.ahoo.wow.api.ledger.LedgerRetrySpec
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The wire envelopes are plain classes (decision D216): they keep a data class's equality, hash and string, and are
 * built through their factories.
 */
class EnvelopeValuesTest {
    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")
    private val function = FunctionInfo(FunctionKind.COMMAND, "sales", "order", "Place")

    private fun result(version: Int = 1) =
        CommandResult.succeeded("c-1", "r-1", "c-1", ref, Hop.committed("sales"), function, version, 7L)

    private fun error() = ErrorInfo.of("sales.order.closed", "closed", listOf(Violation("items", "min", "too few")))

    private fun command(id: String = "c-1") = Command(id, "r-1", ref, mapOf("quantity" to 1))

    private fun execution() = ExecutionId("OrderSaga.onPlaced", ref, 1)

    private fun committed(version: Int = 1) =
        CommandCommitted<Any>("c-2", "sales.order.Place", ref, version, result(), Causation("c-1", "c-1"), execution())

    private fun rejected(id: String = "c-2") =
        CommandRejected<Any>(id, "sales.order.Place", ref, error(), Causation("c-1", "c-1"), execution())

    private fun event(version: Int = 1) =
        StateEvent(ref, version, "c-1", "r-1", "sales.order.Place", 7L, listOf("placed"), mapOf("total" to 1))

    private fun entry(retries: Int = 0) = LedgerEntry(
        service = "sales-service",
        context = "sales",
        subscription = "OrderSaga.onPlaced",
        aggregate = LedgerAggregate("sales", "order", "tenant", "o-1"),
        fromVersion = 1,
        reason = "down",
        failedAt = 1,
        retries = retries,
        retrySpec = LedgerRetrySpec(3, "PT1M", "PT2M"),
        status = "SCHEDULED",
        recordedAt = 2,
    )

    @Test
    fun `an envelope equals another with the same fields, and prints them`() {
        valueSemantics(command(), command(), command("c-9"))
        valueSemantics(result(), result(), result(2))
        valueSemantics(error(), error(), ErrorInfo.of("sales.order.closed", "closed"))
        val signal = WaitSignal(waitId = "w-1", result = result())
        valueSemantics(
            signal,
            WaitSignal(waitId = "w-1", result = result()),
            WaitSignal(waitId = "w-2", result = result())
        )
        valueSemantics(Rejection.of("a", "b"), Rejection.of("a", "b"), Rejection.of("a", "b", mapOf("x" to 1)))
        valueSemantics(event(), event(), event(2))
        valueSemantics(committed(), committed(), committed(2))
        valueSemantics(rejected(), rejected(), rejected("c-3"))
        valueSemantics(WaitPlan.of(Hop.committed()), WaitPlan.COMMITTED, WaitPlan.COMMITTED.within(3.seconds))
        valueSemantics(entry(), entry(), entry(1))
        val hop = Hop.handled("OrderSaga.onPlaced", "sales")
        (hop == Hop.handled("OrderSaga.onPlaced", "sales")).assert().isTrue()
        hop.hashCode().assert().isEqualTo(Hop.handled("OrderSaga.onPlaced", "sales").hashCode())
        (hop == Hop.handled("OrderSaga.onPlaced")).assert().isFalse()
        (hop == hop).assert().isTrue()
        (hop as Any == "handled").assert().isFalse()
    }

    @Test
    fun `a result is built as succeeded, rejected or failed`() {
        result().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result().error.assert().isNull()
        val rejected = CommandResult.rejected("c-1", "r-1", "c-1", ref, Hop.committed(), function, 1, error(), 7L)
        rejected.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        rejected.error.assert().isEqualTo(error())
        val failed = CommandResult.failed("c-1", "r-1", "c-1", ref, Hop.committed(), function, null, error(), 7L)
        failed.status.assert().isEqualTo(CommandResult.Status.FAILED)
        failed.version.assert().isNull()
        failed.format.assert().isEqualTo(CommandResult.FORMAT)
    }

    @Test
    fun `an error and a rejection are built from their factories`() {
        ErrorInfo.of("a", "b").violations.assert().isEmpty()
        ErrorInfo.of("a", "b", extensions = mapOf("x" to 1)).extensions.assert().isEqualTo(mapOf("x" to 1))
        Rejection.of("a", "b").extensions.assert().isEmpty()
        Rejection.of("a", "b", mapOf("x" to 1)).extensions.assert().isEqualTo(mapOf("x" to 1))
    }

    @Test
    fun `Wow's own copy replaces the fields it is given`() {
        result().copyWith(version = 3).assert().isEqualTo(result(3))
        command().copyWith(id = "c-9").assert().isEqualTo(command("c-9"))
        event().copyWith(version = 2).assert().isEqualTo(event(2))
        committed().copyWith(version = 2).assert().isEqualTo(committed(2))
        rejected().copyWith(commandId = "c-3").assert().isEqualTo(rejected("c-3"))
        entry().copyWith(retries = 1).assert().isEqualTo(entry(1))
        Hop.committed().copyWith(context = "sales").assert().isEqualTo(Hop.committed("sales"))
    }

    private fun resolution(outcome: String = LedgerResolution.RESUMED) = LedgerResolution.of(
        "sales-service",
        "sales",
        "OrderSaga.onPlaced",
        LedgerAggregate("sales", "order", "tenant", "o-1"),
        1,
        outcome,
        3,
    )

    @Test
    fun `a retry spec, a resolution and a transformed command are values built from their factories`() {
        valueSemantics(
            LedgerRetrySpec.of(3, "PT1M", "PT2M"),
            LedgerRetrySpec(3, "PT1M", "PT2M", false),
            LedgerRetrySpec.of(3, "PT1M", "PT2M", true),
        )
        LedgerRetrySpec.of(3, "PT1M", "PT2M").toString().assert()
            .isEqualTo("LedgerRetrySpec(maxRetries=3, minBackoff=PT1M, executionTimeout=PT2M, applied=false)")
        valueSemantics(resolution(), resolution(), resolution(LedgerResolution.SKIPPED))
        resolution().toString().assert().isEqualTo(
            "LedgerResolution(service=sales-service, context=sales, subscription=OrderSaga.onPlaced, " +
                "aggregate=${LedgerAggregate("sales", "order", "tenant", "o-1")}, failedAt=1, outcome=RESUMED, " +
                "resolvedAt=3)",
        )
        LedgerResolution.of("s", "c", "p", LedgerAggregate("c", "a", "t", "1"), null, LedgerResolution.NOT_PARKED, 2)
            .failedAt.assert().isNull()
        valueSemantics(
            TransformedFrom.of("sales.order.Import"),
            TransformedFrom("sales.order.Import"),
            TransformedFrom.of("sales.order.Import", "o-1"),
        )
        TransformedFrom.of("sales.order.Import", "o-1").toString().assert()
            .isEqualTo("TransformedFrom(command=sales.order.Import, aggregateId=o-1)")
        (TransformedFrom.of("a.b.C") == TransformedFrom.of("a.b.D")).assert().isFalse()
        TransformedFrom.of("a.b.C", "1").hashCode().assert().isEqualTo(TransformedFrom.of("a.b.C", "1").hashCode())
        LedgerResolution("s", "c", "p", LedgerAggregate("c", "a", "t", "1"), outcome = "SKIPPED", resolvedAt = 2)
            .assert().isEqualTo(
                LedgerResolution.of("s", "c", "p", LedgerAggregate("c", "a", "t", "1"), null, "SKIPPED", 2),
            )
    }

    private fun valueSemantics(value: Any, same: Any, other: Any) {
        (value == value).assert().isTrue()
        (value == same).assert().isTrue()
        value.hashCode().assert().isEqualTo(same.hashCode())
        (value == other).assert().isFalse()
        (value == value.javaClass.simpleName).assert().isFalse()
        value.toString().assert().startsWith("${value.javaClass.simpleName}(").isEqualTo(same.toString())
    }
}
