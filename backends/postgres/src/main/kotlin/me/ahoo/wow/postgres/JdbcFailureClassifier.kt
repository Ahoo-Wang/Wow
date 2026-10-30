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
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException

/**
 * The raw JDBC failures an application's handler meets on its own `DataSource`, a projection writing its read model,
 * say: a connection that failed ([SQLTransientConnectionException], [SQLNonTransientConnectionException], SQL state
 * class `08`) is [FailureClass.SYSTEMIC], the database down for every aggregate. Any other is left to the other
 * classifiers. The backend's own stores throw none of these: they turn theirs into the SPI's `Storage*` failures
 * ([PostgresFailures]), which the kernel classifies.
 *
 * Found by `ServiceLoader`, from this module's `META-INF/services/me.ahoo.wow.spi.subscription.FailureClassifier`:
 * without the module, these failures are recoverable.
 */
internal class JdbcFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is SQLTransientConnectionException, is SQLNonTransientConnectionException -> FailureClass.SYSTEMIC
        is SQLException -> FailureClass.SYSTEMIC.takeIf { failure.sqlState?.startsWith(CONNECTION_EXCEPTION_CLASS) == true }
        else -> null
    }

    private companion object {
        const val CONNECTION_EXCEPTION_CLASS = "08"
    }
}
