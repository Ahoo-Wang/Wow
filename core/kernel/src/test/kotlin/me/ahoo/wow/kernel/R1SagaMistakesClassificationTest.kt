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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Deep review r1, second part (kernel): the mistakes of a saga's code that its derivation finds are classified two
 * ways. A request with a wait or a timeout ([InvalidDerivedRequestException]) and a misused Wow API ([Misuse]) are
 * unrecoverable (KernelFailures: "the code's mistake, which fails the same way every time"), so the aggregate parks at
 * once. A request whose target no contract names, or several do, or a lifecycle command without its target
 * (`omittedTargetOf`), and a schedule with an expected version or of a command no Manifest names
 * (`SagaDerivation.schedule`), fail with a plain `IllegalArgumentException` instead. That is recoverable by default,
 * so the handling is retried through its whole budget, failing the same way each time, before it parks. The same
 * "no Manifest names it" from a decide function's schedule is a [Misuse.Argument].
 */
class R1SagaMistakesClassificationTest {
    data class Order(val id: String)
    data class OrderPlaced(val note: String)
    data class Ship(val orderId: String)

    private val order = AggregateType("shop", "order").ref("t", "o-1")
    private val shipment = AggregateType("shop", "shipment")
    private val event = stateEvent(order, Order("o-1"), OrderPlaced("n"))
    private val execution = ExecutionId("shop", "ShipmentSaga.onPlaced", order, 1)

    /** A saga context whose contracts name no command: as a node whose peer's api module is missing. */
    private fun context() = ExecutionSagaContext(execution, event, IdGenerators.DEFAULT, CommandTypes.NONE)

    private fun classOf(mistake: () -> Unit): FailureClass {
        val failure = runCatching(mistake).exceptionOrNull()
        checkNotNull(failure) { "The mistake was not refused." }
        return FailureClassification.DEFAULT.classify(failure, null)
    }

    @Test
    fun `a request with a timeout is the saga's mistake, unrecoverable`() {
        classOf {
            context().commandsOf(listOf(CommandRequest.of(Ship("o-1"), type = shipment, timeout = 1.seconds)))
        }.assert().isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `a request whose target no contract names is the saga's mistake, unrecoverable as the others`() {
        classOf { context().commandsOf(listOf(CommandRequest.of(Ship("o-1")))) }
            .assert().isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `a lifecycle command without its target is the saga's mistake, unrecoverable as the others`() {
        classOf { context().commandsOf(listOf(CommandRequest.of(DeleteAggregate(), "o-1"))) }
            .assert().isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `a schedule of a command no Manifest names is the saga's mistake, unrecoverable as a decide function's`() {
        classOf {
            context().schedule("remind", ScheduleRule.At(1_000), CommandRequest.of(Ship("o-1"), "s-1", shipment))
        }.assert().isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `a schedule with an expected version is the saga's mistake, unrecoverable as the others`() {
        classOf {
            context().schedule(
                "remind",
                ScheduleRule.At(1_000),
                CommandRequest.of(Ship("o-1"), "s-1", shipment, expectedVersion = 3),
            )
        }.assert().isEqualTo(FailureClass.UNRECOVERABLE)
    }
}
