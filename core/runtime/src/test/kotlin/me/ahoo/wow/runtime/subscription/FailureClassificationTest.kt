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
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException

/** How a handler's failure is classified (decisions D229 G-6, D243): its `@Retry`, the classifiers, the default. */
class FailureClassificationTest {
    private val classification = FailureClassification.DEFAULT

    @Test
    fun `the runtime takes connection failures for systemic and anything else for recoverable`() {
        listOf(
            StorageUnavailableException("The store is unavailable; retry later."),
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
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.RECOVERABLE) }
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
    fun `the backends on the class path contribute no classifier to the runtime's own tests`() {
        FailureClassification.LOADED.assert().isEmpty()
    }
}
