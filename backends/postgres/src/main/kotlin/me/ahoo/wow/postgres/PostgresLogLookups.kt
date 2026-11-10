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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.log.EventStreamRecord
import java.sql.Connection

/** Where a stream is found in its aggregate's log table by a key: its request's stream, and the aggregate's version. */
internal class PostgresLogLookups(private val logRows: PostgresLogRows) {
    fun findByRequestIdIn(
        connection: Connection,
        table: AggregateLogTable,
        tenantId: String,
        requestId: String,
    ): EventStreamRecord? = connection.prepareStatement(table.byRequest).use { query ->
        query.setString(1, tenantId)
        query.setString(2, requestId)
        query.executeQuery().use { rows -> if (rows.next()) logRows.streamOf(table.aggregate, rows) else null }
    }

    fun currentVersion(connection: Connection, table: AggregateLogTable, ref: AggregateRef): Int =
        connection.prepareStatement(table.currentVersion).use { query ->
            query.setString(1, ref.id)
            query.setString(2, ref.tenantId)
            query.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
}
