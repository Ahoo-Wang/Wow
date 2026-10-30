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

package me.ahoo.wow.postgres

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException
import java.util.ServiceLoader

/** The raw JDBC failures of an application's handler, which this module classifies wherever it is on the classpath. */
class JdbcFailureClassifierTest {
    @Test
    fun `the module's classifier should be found on the classpath`() {
        ServiceLoader.load(FailureClassifier::class.java).map { it.javaClass.name }
            .assert().contains(JdbcFailureClassifier::class.java.name)
        FailureClassification.MODULES.map { it.javaClass.name }.assert().contains(
            JdbcFailureClassifier::class.java.name
        )
    }

    @Test
    fun `a connection that failed should be systemic, any other JDBC failure left to the other classifiers`() {
        // The kernel's classification, the runtime's and the test DSL's alike.
        val classification = FailureClassification.DEFAULT
        listOf(
            SQLTransientConnectionException("pool timed out"),
            SQLNonTransientConnectionException("refused"),
            SQLException("connection failure", "08006"),
            IllegalStateException("The projection failed.", SQLException("unable to connect", "08001")),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.SYSTEMIC) }
        listOf(
            SQLException("unique violation", "23505"),
            SQLException("no state"),
        ).forEach { classification.classify(it, null).assert().isEqualTo(FailureClass.RECOVERABLE) }
        JdbcFailureClassifier().classify(IllegalStateException("broken")).assert().isNull()
    }
}
