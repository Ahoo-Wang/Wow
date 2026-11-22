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
import me.ahoo.wow.spi.subscription.FailureDescriber
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.SQLException

/**
 * #6588: a JDBC failure parks with its SQL state and the names the server gives in its structured fields, never its
 * message or `detail`, which quote the row: a unique violation, as PostgreSQL writes it.
 */
class JdbcFailureDescriberTest {
    private val describer = JdbcFailureDescriber()

    /** What [failure] is described as; null when it is not. */
    private fun told(failure: Throwable): String? = describer.describe(failure)?.text

    @Test
    fun `the module should register its describer for ServiceLoader, as a node finds it`() {
        java.util.ServiceLoader.load(FailureDescriber::class.java).map { it.javaClass.name }.assert()
            .contains(JdbcFailureDescriber::class.java.name)
    }

    @Test
    fun `a PostgreSQL unique violation should be its state, constraint and table, without the row`() {
        val server = ServerErrorMessage(
            "SERROR\u0000C23505\u0000Mduplicate key value violates unique constraint \"readers_email_key\"\u0000" +
                "DKey (email)=(alice.smith@example.com) already exists.\u0000sprobe\u0000treaders\u0000" +
                "nreaders_email_key\u0000",
        )
        val reason = told(PSQLException(server))
        reason.assert().isEqualTo("sql state '23505' constraint 'readers_email_key' table 'readers'")
    }

    @Test
    fun `another JDBC failure should be its state and error code, and a name it does not take left out`() {
        told(SQLException("Value alice.smith@example.com too long", "22001", 1406)).assert()
            .isEqualTo("sql state '22001' error code 1406")
        told(SQLException("alice.smith@example.com")).assert().isNull()
        val quoted = ServerErrorMessage("SERROR\u0000C23505\u0000Mm\u0000n\"my constraint\"\u0000")
        told(PSQLException(quoted)).assert().isEqualTo("sql state '23505'")
        told(SQLException("x", "")).assert().isNull()
    }
}
