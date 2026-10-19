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

import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import java.sql.SQLException

/**
 * Classifies PostgreSQL's server states of a server that is not there as systemic (decision D243), for a handler that
 * writes to PostgreSQL itself: `57P01` admin shutdown, `57P02` crash shutdown, `57P03` cannot connect now, `53300`
 * too many connections. Every aggregate of the partition would fail the same way, so the subscription pauses it. A
 * failed connection (SQL state class `08`) the runtime classifies itself; a deadlock or a serialization failure stays
 * the aggregate's own, retried within its budget.
 *
 * Found by [java.util.ServiceLoader] (`META-INF/services`) when this backend is on the class path.
 */
internal class PostgresFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? =
        FailureClass.SYSTEMIC.takeIf { failure is SQLException && failure.sqlState in UNAVAILABLE_STATES }

    private companion object {
        val UNAVAILABLE_STATES = setOf("57P01", "57P02", "57P03", "53300")
    }
}
