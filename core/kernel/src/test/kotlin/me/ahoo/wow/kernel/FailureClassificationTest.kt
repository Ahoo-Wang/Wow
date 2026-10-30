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
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.test.rejectedResult
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
}
