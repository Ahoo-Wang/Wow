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

package me.ahoo.wow.runtime.subscription

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.UnreadableEventException
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageTimeoutException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.transport.TransportRefusedException
import me.ahoo.wow.spi.transport.TransportUnavailableException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import kotlin.time.Duration.Companion.seconds

/** How a handler's failure is classified (decisions D229 G-6, D243): its `@Retry`, the classifiers, the default. */
class FailureClassificationTest {
    private val classification = FailureClassification.DEFAULT

    @Test
    fun `the runtime takes connection failures for systemic and anything else for recoverable`() {
        listOf(
            StorageUnavailableException("The store is unavailable; retry later."),
            TransportUnavailableException("The broker is unavailable; retry later."),
            ConnectException("Connection refused"),
            SocketException("Connection reset"),
            UnknownHostException("views.internal"),
            HttpConnectTimeoutException("HTTP connect timed out"),
            SQLTransientConnectionException("pool timed out"),
            SQLNonTransientConnectionException("refused"),
            SQLException("connection failure", "08006"),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.SYSTEMIC) }
        listOf(
            IllegalStateException("broken"),
            IOException("disk"),
            SQLException("unique violation", "23505"),
            SQLException("no state"),
            HandlerTimeoutException("projection", cart("x"), 1, kotlin.time.Duration.parse("1s")),
            StorageTimeoutException(java.time.Duration.ofSeconds(1)),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.RECOVERABLE) }
    }

    @Test
    fun `a store at its capacity is backpressure, though it is unavailable to every other caller`() {
        val busy = StorageBusyException("PostgreSQL has no connection to spare; retry later.")
        (busy is StorageUnavailableException).assert().isTrue()
        classification.classify(busy, null).assert().isEqualTo(FailureClass.BACKPRESSURE)
        classification.classify(IllegalStateException("The saga failed.", busy), null).assert()
            .isEqualTo(FailureClass.BACKPRESSURE)
        classification.classify(StorageUnavailableException("PostgreSQL is unavailable; retry later."), null).assert()
            .isEqualTo(FailureClass.SYSTEMIC)
    }

    @Test
    fun `a value a store or a transport refused for good is unrecoverable, wherever it was wrapped`() {
        val refused = StorageRefusedException("PostgreSQL refuses the value (SQLSTATE 22P05).")
        classification.classify(refused, null).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(IllegalStateException("The sink failed.", refused), null).assert()
            .isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(TransportRefusedException("The record is too large."), null).assert()
            .isEqualTo(FailureClass.UNRECOVERABLE)
    }

    @Test
    fun `a cause is classified when the failure itself is not, through a cycle too`() {
        val wrapped = IllegalStateException("The projection failed.", ConnectException("Connection refused"))
        classification.classify(wrapped, null).assert().isEqualTo(FailureClass.SYSTEMIC)
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        classification.classify(first, null).assert().isEqualTo(FailureClass.RECOVERABLE)
    }

    @Test
    fun `the handler's lists come first, then the application's classifiers, then the runtime's`() {
        val unavailable = StorageUnavailableException("The store is unavailable; retry later.")
        classification.classify(
            unavailable,
            HandlerRetry(recoverable = listOf(StorageUnavailableException::class.java)),
        ).assert().isEqualTo(FailureClass.RECOVERABLE)
        val application = FailureClassification.of(
            listOf(
                FailureClassifier { if (it is IllegalArgumentException) FailureClass.UNRECOVERABLE else null },
                FailureClassifier { FailureClass.RECOVERABLE.takeIf { _ -> it is StorageUnavailableException } },
            ),
        )
        application.classify(IllegalArgumentException("bad"), null).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        application.classify(unavailable, null).assert().isEqualTo(FailureClass.RECOVERABLE)
        application.classify(
            IllegalArgumentException("bad"),
            HandlerRetry(maxAttempts = 2),
        ).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        FailureClassifier.NONE.classify(unavailable).assert().isNull()
        BuiltInFailures.classify(IllegalStateException()).assert().isNull()
    }

    @Test
    fun `a downstream that pushes back is backpressure, one that is down systemic`() {
        fun problem(code: String, status: Int, retryAfter: kotlin.time.Duration? = null) =
            WowProblemException(code, status, Retry.SAME_REQUEST, retryAfter, "$code failed")
        listOf(
            CommandBusyException(cart("x"), "c-1", "busy"),
            IllegalStateException("The query was not admitted.", StorageBusyException("The store is busy.")),
            problem(FrameworkErrors.COMMAND_BUSY, 429, 1.seconds),
            problem(FrameworkErrors.WAIT_TIMEOUT, 504),
            problem(FrameworkErrors.QUERY_BUSY, 429),
            problem("shop.quota.exceeded", 429, 2.seconds),
            IllegalStateException("The saga failed.", problem(FrameworkErrors.WAIT_TIMEOUT, 504)),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.BACKPRESSURE) }
        classification.classify(problem(FrameworkErrors.STORAGE_UNAVAILABLE, 503, 1.seconds), null).assert()
            .isEqualTo(FailureClass.SYSTEMIC)
        // A 429 without a Retry-After, or another code, is the aggregate's own.
        classification.classify(problem("shop.quota.exceeded", 429), null).assert().isEqualTo(FailureClass.RECOVERABLE)
        classification.classify(problem(FrameworkErrors.COMMAND_CONTENDED, 409), null).assert()
            .isEqualTo(FailureClass.RECOVERABLE)
        FailureClassification.retryAfterOf(IllegalStateException("x", problem("shop.quota", 429, 2.seconds))).assert()
            .isEqualTo(2.seconds)
        FailureClassification.retryAfterOf(IllegalStateException("x")).assert().isNull()
    }

    @Test
    fun `an event the kernel could not read classifies as what it could not read`() {
        fun unreadable(cause: RuntimeException) =
            UnreadableEventException(cart("c-1"), 3, "shop.cart.ItemsAdded", 1, cause)
        val decoding = IllegalArgumentException("no kind")
        val unrecoverable = HandlerRetry(unrecoverable = listOf(IllegalArgumentException::class.java))
        // A handler's own lists see the cause, as they see the bare failure.
        classification.classify(decoding, unrecoverable).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        classification.classify(unreadable(decoding), unrecoverable).assert().isEqualTo(FailureClass.UNRECOVERABLE)
        // Without a list, the default: retried, then parked, as before.
        classification.classify(decoding, null).assert().isEqualTo(FailureClass.RECOVERABLE)
        classification.classify(unreadable(decoding), null).assert().isEqualTo(FailureClass.RECOVERABLE)
        // A classifier matching the cause still matches.
        val unavailable = StorageUnavailableException("The store is unavailable; retry later.")
        classification.classify(unreadable(unavailable), null).assert().isEqualTo(FailureClass.SYSTEMIC)
        unreadable(decoding).message.assert()
            .isEqualTo("${cart("c-1")} v3: the event shop.cart.ItemsAdded@1 does not read: no kind")
    }
}
