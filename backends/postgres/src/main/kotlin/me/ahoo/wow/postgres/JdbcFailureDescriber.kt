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

import me.ahoo.wow.spi.subscription.FailureDescriber
import me.ahoo.wow.spi.subscription.FailureDescription
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.code
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.name
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.phrase
import org.postgresql.util.PSQLException
import java.sql.SQLException

/** The words of JDBC's descriptions. */
internal enum class JdbcPhrase {
    SQL_STATE,
    ERROR_CODE,
    CONSTRAINT,
    TABLE,
    COLUMN,
}

/**
 * The raw JDBC failures an application's handler meets on its own `DataSource`, described for the reason a parked
 * aggregate records: the SQL state, the driver's error code when it gives one, and, for PostgreSQL's, the constraint,
 * the table and the column the server names in its structured fields (`sql state '23505' constraint
 * 'readers_email_key' table 'readers'`). Never the message or the server's `detail`, which quote the row
 * (`Key (email)=(…) already exists.`). A name a description does not take (a quoted identifier with a space, say) is
 * left out.
 *
 * Found by `ServiceLoader`, from this module's `META-INF/services/me.ahoo.wow.spi.subscription.FailureDescriber`.
 */
internal class JdbcFailureDescriber : FailureDescriber {
    override fun describe(failure: Throwable): FailureDescription? {
        if (failure !is SQLException) return null
        val server = (failure as? PSQLException)?.serverErrorMessage
        val parts = buildList {
            named(JdbcPhrase.SQL_STATE, failure.sqlState)
            if (failure.errorCode != 0) {
                add(phrase(JdbcPhrase.ERROR_CODE))
                add(code(failure.errorCode.toLong()))
            }
            named(JdbcPhrase.CONSTRAINT, server?.constraint)
            named(JdbcPhrase.TABLE, server?.table)
            named(JdbcPhrase.COLUMN, server?.column)
        }
        return parts.takeIf { it.isNotEmpty() }?.let { FailureDescription.of(*it.toTypedArray()) }
    }

    private fun MutableList<FailureDescription.Part>.named(what: JdbcPhrase, value: String?) {
        val named = value?.takeIf { it.isNotEmpty() }?.let { runCatching { name(it) }.getOrNull() } ?: return
        add(phrase(what))
        add(named)
    }
}
