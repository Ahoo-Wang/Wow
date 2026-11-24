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
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.UnrecoverableHandlingException
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.test.rejectedResult
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration.Companion.seconds

/**
 * The classification the runtime's subscriptions and the test DSL share: a handler's `@Retry`, the classifiers given,
 * the built-in rules, and the rejections that park at once.
 */
class FailureClassificationTest {
    private val classification = FailureClassification.DEFAULT

    @Test
    fun `the built-in rules know a downstream that is down, one that pushes back and a value refused for good`() {
        classification.classify(StorageUnavailableException("down"), null).assert().isEqualTo(FailureClass.SYSTEMIC)
        classification.classify(IllegalStateException("wrapped", StorageUnavailableException("down")), null).assert()
            .isEqualTo(FailureClass.SYSTEMIC)
        classification.classify(StorageBusyException("busy"), null).assert().isEqualTo(FailureClass.BACKPRESSURE)
        val busy = WowProblemException(FrameworkCodes.COMMAND_BUSY, 429, Retry.SAME_REQUEST, 1.seconds, "busy")
        classification.classify(busy, null).assert().isEqualTo(FailureClass.BACKPRESSURE)
        FailureClassification.retryAfterOf(IllegalStateException("x", busy)).assert().isEqualTo(1.seconds)
        classification.classify(StorageRefusedException("refused"), null).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(IllegalStateException("broken"), null).assert().isEqualTo(FailureClass.RECOVERABLE)
        // A Wow API the handler used in a way it cannot serve fails the same way every time: the code's mistake.
        classification.classify(Misuse.Argument("No aggregate has a state of X."), null).assert()
            .isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(Misuse.State("No URL calls context x."), null).assert()
            .isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `the handler's lists come first, then the classifiers given, then the built-in rules`() {
        val down = StorageUnavailableException("down")
        classification.classify(down, HandlerRetry(recoverable = listOf(StorageUnavailableException::class.java)))
            .assert().isEqualTo(FailureClass.RECOVERABLE)
        val given = FailureClassification.of(
            listOf(FailureClassifier { FailureClass.UNRECOVERABLE.takeIf { _ -> it is IllegalArgumentException } }),
        )
        given.classify(IllegalArgumentException("bad"), null).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        given.classify(down, null).assert().isEqualTo(FailureClass.SYSTEMIC)
    }

    @Test
    fun `the kernel's own failures are classified before a handler's lists, whatever they name`() {
        val retryAll = HandlerRetry(
            recoverable = listOf(IllegalStateException::class.java, IllegalArgumentException::class.java),
        )
        listOf(
            UndeclaredSendException("shop.saga", String::class.java, "derives"),
            InvalidDerivedRequestException("No command request."),
            Misuse.State("No URL calls context x."),
            Misuse.Argument("No aggregate has a state of X."),
            // Wrapped, as a handler may rethrow it.
            IllegalStateException("wrapped", UndeclaredSendException("shop.saga", String::class.java, "derives")),
        ).forEach { classification.classify(it, retryAll).assert().isEqualTo(FailureClass.UNRECOVERABLE) }
        val parkAll = HandlerRetry(unrecoverable = listOf(IllegalStateException::class.java))
        val cart = AggregateType("shop", "cart").ref("tenant", "c-1")
        val late = HandlerTimeoutException("shop.saga", cart, 3, 5.seconds)
        classification.classify(late, parkAll).assert().isEqualTo(FailureClass.RECOVERABLE)
        // The handler's own failure of the same class is still its list's.
        classification.classify(IllegalStateException("mine"), parkAll).assert().isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `the application's classifiers come before the modules', the modules' before the caller's own and the built-in`() {
        val down = StorageUnavailableException("down")
        val module = FailureClassifier {
            when (it) {
                is StorageUnavailableException -> FailureClass.RECOVERABLE
                is IllegalArgumentException -> FailureClass.SYSTEMIC
                else -> null
            }
        }
        val own = FailureClassifier { FailureClass.BACKPRESSURE.takeIf { _ -> it is IllegalArgumentException } }
        val modules = FailureClassification.of(own = listOf(own), modules = listOf(module))
        // A module's rule overrides the built-in one, and the caller's own.
        modules.classify(down, null).assert().isEqualTo(FailureClass.RECOVERABLE)
        modules.classify(IllegalArgumentException("bad"), null).assert().isEqualTo(FailureClass.SYSTEMIC)
        // The application's overrides a module's.
        val application =
            FailureClassifier { FailureClass.UNRECOVERABLE.takeIf { _ -> it is IllegalArgumentException } }
        FailureClassification.of(listOf(application), listOf(own), listOf(module))
            .classify(IllegalArgumentException("bad"), null).assert().isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `a failure a handler says no retry changes should park at once, unless its own @Retry says otherwise`() {
        class UnknownBuyerException : UnrecoverableHandlingException("The notification service knows no such buyer.")
        classification.classify(UnrecoverableHandlingException("No retry changes this."), null)
            .assert().isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(UnknownBuyerException(), null).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(IllegalStateException("wrapped", UnknownBuyerException()), null)
            .assert().isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(
            UnknownBuyerException(),
            HandlerRetry(recoverable = listOf(UnknownBuyerException::class.java)),
        ).assert().isEqualTo(FailureClass.RECOVERABLE)
    }

    @Test
    fun `without the modules that own them, raw JDBC and network failures are recoverable`() {
        // Neither wow-postgres nor wow-client is on this classpath: no module classifies their technologies' failures.
        FailureClassification.MODULES.assert().isEmpty()
        listOf(
            ConnectException("refused"),
            SQLTransientConnectionException("pool timed out"),
            SQLException("connection failure", "08006"),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.RECOVERABLE) }
    }

    @Test
    fun `a call's rejection no handler takes is a rejection, which parks at once`() {
        val command = Command("c-1", "r-1", AggregateType("shop", "cart").ref("t", "cart-1"), AddItem("mug", 1))
        val rejected = CommandRejectedException(rejectedResult(command, "cart.full", "The cart is full."))
        val thrown = CallRejectedHandlingException(rejected)
        thrown.rejected.assert().isSameAs(rejected)
        thrown.error.code.assert().isEqualTo("cart.full")
        (thrown is HandlingRejectedException).assert().isTrue()
    }

    data class Order(val id: String)
    data class OrderPlaced(val note: String)
    data class Ship(val orderId: String)

    /** A saga context whose contracts name no command: as a node whose peer's api module is missing. */
    private fun sagaContext(): ExecutionSagaContext {
        val order = AggregateType("shop", "order").ref("t", "o-1")
        val execution = ExecutionId("shop", "ShipmentSaga.onPlaced", order, 1)
        return ExecutionSagaContext(
            execution,
            stateEvent(order, Order("o-1"), OrderPlaced("n")),
            IdGenerators.DEFAULT,
            CommandTypes.NONE,
        )
    }

    private val shipment = AggregateType("shop", "shipment")

    private fun classOf(mistake: () -> Unit): FailureClass =
        classification.classify(checkNotNull(runCatching(mistake).exceptionOrNull()), null)

    @Test
    fun `a saga's own mistakes should be unrecoverable wherever its derivation finds them`() {
        // #6795: each fails the same way on every retry, so the aggregate parks at once, as a wait or a timeout on a
        // request does (InvalidDerivedRequestException) and as a decide function's misused schedule does (Misuse).
        val mistakes = mapOf<String, () -> Unit>(
            "a request with a timeout" to {
                sagaContext().commandsOf(listOf(CommandRequest.of(Ship("o-1"), type = shipment, timeout = 1.seconds)))
            },
            "a request whose target no contract names" to {
                sagaContext().commandsOf(listOf(CommandRequest.of(Ship("o-1"))))
            },
            "a lifecycle command without its target" to {
                sagaContext().commandsOf(listOf(CommandRequest.of(DeleteAggregate(), "o-1")))
            },
            "a schedule of a command no Manifest names" to {
                sagaContext().schedule("remind", ScheduleRule.At(1_000), CommandRequest.of(Ship("o-1"), "s-1", shipment))
            },
            "a schedule with an expected version" to {
                sagaContext().schedule(
                    "remind",
                    ScheduleRule.At(1_000),
                    CommandRequest.of(Ship("o-1"), "s-1", shipment, expectedVersion = 3),
                )
            },
            "a context that is not the kernel's" to {
                derivationOf(ExecutionContext(ExecutionId("shop", "ShipmentSaga.onPlaced", shipment.ref("t", "s"), 1)))
            },
        )
        mistakes.forEach { (mistake, make) ->
            classOf(make).assert().describedAs(mistake).isEqualTo(FailureClass.UNRECOVERABLE)
        }
    }
}
