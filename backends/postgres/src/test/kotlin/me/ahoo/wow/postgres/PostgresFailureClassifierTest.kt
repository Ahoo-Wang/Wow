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
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.ServiceLoader

/** A PostgreSQL server that is not there is systemic (decision D243); the rest it leaves to the runtime. */
class PostgresFailureClassifierTest {
    @Test
    fun `a server shutting down, starting or full is systemic, others unknown to it`() {
        val classifier = PostgresFailureClassifier()
        listOf("57P01", "57P02", "57P03", "53300").forEach {
            classifier.classify(SQLException("unavailable", it)).assert().isEqualTo(FailureClass.SYSTEMIC)
        }
        classifier.classify(SQLException("deadlock", "40P01")).assert().isNull()
        classifier.classify(IllegalStateException()).assert().isNull()
    }

    @Test
    fun `the runtime finds it on the class path`() {
        ServiceLoader.load(FailureClassifier::class.java).map { it.javaClass.name }.assert()
            .contains(PostgresFailureClassifier::class.java.name)
    }
}
