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

package me.ahoo.wow.client

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.util.ServiceLoader

/** The JDK's raw network failures of an application's handler, which this module classifies on the classpath. */
class NetworkFailureClassifierTest {
    @Test
    fun `the module's classifier should be found on the classpath`() {
        ServiceLoader.load(FailureClassifier::class.java).map { it.javaClass.name }
            .assert().contains(NetworkFailureClassifier::class.java.name)
        FailureClassification.MODULES.map { it.javaClass.name }.assert().contains(
            NetworkFailureClassifier::class.java.name
        )
    }

    @Test
    fun `a downstream that cannot be reached should be systemic, any other failure left to the other classifiers`() {
        // The kernel's classification, the runtime's and the test DSL's alike.
        val classification = FailureClassification.DEFAULT
        listOf(
            ConnectException("Connection refused"),
            SocketException("Connection reset"),
            UnknownHostException("carrier.example"),
            HttpConnectTimeoutException("HTTP connect timed out"),
            IllegalStateException("The reactor failed.", ConnectException("Connection refused")),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.SYSTEMIC) }
        listOf(
            IOException("disk"),
            HttpTimeoutException("request timed out"),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.RECOVERABLE) }
        NetworkFailureClassifier().classify(IllegalStateException("broken")).assert().isNull()
    }
}
