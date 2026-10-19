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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Roadmap P.3: a handler's own in-process retry budget, from its `@Retry` and its processor's. */
class HandlerRetryTest {
    @Retry(maxAttempts = 3, timeout = "2s", maxBackoff = "1m")
    private class Notifier {
        @Retry(initialBackoff = "500ms", timeout = "PT10S")
        fun onShipped() = Unit

        fun onPlaced() = Unit
    }

    private fun functionRetry(name: String): Retry? =
        Notifier::class.java.getDeclaredMethod(name).getAnnotation(Retry::class.java)

    private val processorRetry: Retry? = Notifier::class.java.getAnnotation(Retry::class.java)

    @Test
    fun `a handler's settings override its processor's, setting by setting`() {
        HandlerRetry.of(functionRetry("onShipped"), processorRetry).assert().isEqualTo(
            HandlerRetry(
                maxAttempts = 3,
                initialBackoff = 500.milliseconds,
                maxBackoff = 1.minutes,
                timeout = 10.seconds
            ),
        )
        HandlerRetry.of(functionRetry("onPlaced"), processorRetry).assert()
            .isEqualTo(HandlerRetry(maxAttempts = 3, maxBackoff = 1.minutes, timeout = 2.seconds))
        HandlerRetry.of(functionRetry("onShipped"), null).assert()
            .isEqualTo(HandlerRetry(initialBackoff = 500.milliseconds, timeout = 10.seconds))
        HandlerRetry.of(null, null).assert().isNull()
    }

    @Test
    fun `an annotation that gives nothing is no budget`() {
        HandlerRetry.of().assert().isNull()
        HandlerRetry.of(maxAttempts = 0, timeout = " ").assert().isNull()
        HandlerRetry.NONE.isEmpty.assert().isTrue()
        HandlerRetry(maxAttempts = 1).isEmpty.assert().isFalse()
    }

    @Test
    fun `the settings not given come from the budget below`() {
        val base = HandlerRetry(maxAttempts = 4, multiplier = 3.0, timeout = 1.seconds)
        HandlerRetry(timeout = 5.seconds).over(base).assert()
            .isEqualTo(HandlerRetry(maxAttempts = 4, multiplier = 3.0, timeout = 5.seconds))
        HandlerRetry(timeout = 5.seconds).over(null).assert().isEqualTo(HandlerRetry(timeout = 5.seconds))
    }

    @Test
    fun `an invalid setting is refused`() {
        assertThrows<IllegalArgumentException> { HandlerRetry.of(timeout = "soon") }
            .message.assert().contains("timeout").contains("soon")
        assertThrownBy<IllegalArgumentException> { HandlerRetry.of(maxAttempts = -1) }
        assertThrownBy<IllegalArgumentException> { HandlerRetry(maxAttempts = 0) }
        assertThrownBy<IllegalArgumentException> { HandlerRetry(initialBackoff = (-1).seconds) }
        assertThrownBy<IllegalArgumentException> { HandlerRetry(maxBackoff = (-1).seconds) }
        assertThrownBy<IllegalArgumentException> { HandlerRetry(multiplier = 0.5) }
        assertThrownBy<IllegalArgumentException> { HandlerRetry(timeout = 0.seconds) }
    }

    @Test
    fun `a binding carries its handler's budget`() {
        val retry = HandlerRetry(maxAttempts = 2)
        ProcessorBinding.project<Any>("View", "onEvent", null, retry = retry) { _, _ -> }.retry.assert()
            .isEqualTo(retry)
        ProcessorBinding.react<Any>("Mail", "send", null, retry = retry) { _, _ -> }.retry.assert().isEqualTo(retry)
        ProcessorBinding.saga<Any>("Saga", "derive", null, retry = retry) { _, _ -> emptyList() }.retry.assert()
            .isEqualTo(retry)
        ProcessorBinding.project<Any>("View", "other", null) { _, _ -> }.retry.assert().isNull()
    }

    @Retry(unrecoverable = [IllegalArgumentException::class], recoverable = [NumberFormatException::class])
    private class Classifying {
        @Retry(recoverable = [IllegalStateException::class])
        fun own() = Unit

        fun inherited() = Unit
    }

    private fun classifying(name: String): HandlerRetry? = HandlerRetry.of(
        Classifying::class.java.getDeclaredMethod(name).getAnnotation(Retry::class.java),
        Classifying::class.java.getAnnotation(Retry::class.java),
    )

    @Test
    fun `the exception classes a handler classifies replace its processor's, list by list`() {
        classifying("inherited").assert().isEqualTo(
            HandlerRetry(
                recoverable = listOf(NumberFormatException::class.java),
                unrecoverable = listOf(IllegalArgumentException::class.java),
            ),
        )
        classifying("own").assert().isEqualTo(
            HandlerRetry(
                recoverable = listOf(IllegalStateException::class.java),
                unrecoverable = listOf(IllegalArgumentException::class.java),
            ),
        )
        HandlerRetry.of(0, "", "", "", emptyList(), emptyList()).assert().isNull()
        assertThrows<IllegalArgumentException> { HandlerRetry(recoverable = emptyList()) }
        assertThrows<IllegalArgumentException> { HandlerRetry(unrecoverable = emptyList()) }
    }

    @Test
    fun `the nearer listed class decides, the recoverable one on a tie`() {
        val retry = checkNotNull(classifying("inherited"))
        // NumberFormatException extends IllegalArgumentException: its own class is nearer.
        retry.classify(NumberFormatException("x")).assert().isEqualTo(FailureClass.RECOVERABLE)
        retry.classify(IllegalArgumentException("x")).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        retry.classify(IllegalStateException("x")).assert().isNull()
        HandlerRetry(
            recoverable = listOf(RuntimeException::class.java),
            unrecoverable = listOf(RuntimeException::class.java),
        ).classify(IllegalStateException("x")).assert().isEqualTo(FailureClass.RECOVERABLE)
        HandlerRetry(maxAttempts = 2).classify(IllegalStateException("x")).assert().isNull()
    }
}
