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

package me.ahoo.wow.clickhouse

import com.clickhouse.client.api.ConnectionInitiationException
import com.clickhouse.client.api.ServerException
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ServiceLoader

class ClickHouseFailureClassifierTest {
    private val classifier = ClickHouseFailureClassifier()

    private fun server(code: Int) = ServerException(code, "Code: $code. DB::Exception", 500, "q-1")

    @Test
    fun `a ClickHouse that cannot be reached is systemic`() {
        classifier.classify(ConnectionInitiationException("Failed to connect", ConnectException("refused")))
            .assert().isEqualTo(FailureClass.SYSTEMIC)
        classifier.classify(ConnectException("Server returned 503")).assert().isEqualTo(FailureClass.SYSTEMIC)
        classifier.classify(SocketTimeoutException("Read timed out")).assert().isEqualTo(FailureClass.SYSTEMIC)
        classifier.classify(UnknownHostException("clickhouse")).assert().isEqualTo(FailureClass.SYSTEMIC)
    }

    @Test
    fun `an overloaded server is systemic, a failed statement is not`() {
        ClickHouseFailureClassifier.UNAVAILABLE_CODES.forEach {
            classifier.classify(server(it)).assert().isEqualTo(FailureClass.SYSTEMIC)
        }
        // A statement past its own max_execution_time is the query's timeout, not the store's.
        classifier.classify(server(ClickHouseExecutor.TIMEOUT_EXCEEDED)).assert().isNull()
        classifier.classify(server(ServerException.TABLE_NOT_FOUND)).assert().isNull()
        classifier.classify(IllegalStateException("broken")).assert().isNull()
    }

    @Test
    fun `the classifier is found on the class path`() {
        ServiceLoader.load(FailureClassifier::class.java).map { it::class }
            .assert().contains(ClickHouseFailureClassifier::class)
    }
}
