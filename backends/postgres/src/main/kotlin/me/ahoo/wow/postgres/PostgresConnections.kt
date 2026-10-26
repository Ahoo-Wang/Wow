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

import java.sql.Connection
import javax.sql.DataSource

/**
 * Runs [block] with a connection of this data source in autocommit mode, and returns the connection with the
 * autocommit mode it was borrowed in (finding R2.2-1).
 *
 * Every PostgreSQL store writes with single statements that must be durable once they return: an append reported
 * committed, a lease acquired, a checkpoint saved. A pool configured with `auto-commit=false` (a common Hibernate
 * setting a host may share with Wow) would open an implicit transaction for them that the pool rolls back when the
 * connection is returned, after the store reported success. So the stores do not rely on the pool's default: they
 * turn autocommit on for what they borrow, which costs nothing when it is already on (pgjdbc and HikariCP skip a
 * change to the current mode), and put it back as they found it, so the pool and the next borrower see the
 * connection unchanged. Refusing such a pool instead would make the host give Wow a pool of its own.
 *
 * The few operations that need a transaction open one on the connection this gives them and restore autocommit
 * after it ([inTransaction]).
 */
internal inline fun <T> DataSource.autoCommitted(block: (Connection) -> T): T = connection.use { connection ->
    val original = connection.autoCommit
    if (!original) {
        connection.autoCommit = true
    }
    try {
        block(connection)
    } finally {
        if (!original) {
            connection.autoCommit = false
        }
    }
}

/**
 * Runs [block] in one transaction of [connection]: commits when it returns, rolls back whatever it did when it
 * throws, and leaves the connection in the autocommit mode it found it in. The transaction is bounded on the server
 * by the connection's network timeout first ([PostgresTimeouts.boundTransaction]), and only it: `SET LOCAL` ends with it.
 */
internal inline fun <T> inTransaction(connection: Connection, block: () -> T): T {
    val original = connection.autoCommit
    connection.autoCommit = false
    var committed = false
    try {
        PostgresTimeouts.boundTransaction(connection)
        return block().also {
            connection.commit()
            committed = true
        }
    } finally {
        if (!committed) {
            connection.rollback()
        }
        connection.autoCommit = original
    }
}
