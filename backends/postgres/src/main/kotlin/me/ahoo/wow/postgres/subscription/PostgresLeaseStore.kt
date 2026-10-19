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

package me.ahoo.wow.postgres.subscription

import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.PostgresFormat
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.jdbc
import me.ahoo.wow.spi.StoreDdl
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * The [LeaseStore] on PostgreSQL: one row per (group, partition) in `wow_lease` and one per (group, node), with the
 * node's internal address, in `wow_lease_member`.
 *
 * Every operation is one autocommitted statement. An acquisition is an upsert whose update applies only when the
 * stored lease has expired or belongs to the acquiring node; PostgreSQL re-checks that condition against the latest
 * row after waiting for a concurrent writer, so concurrent acquisitions have exactly one winner. A release keeps the
 * row with expiry 0, so the next acquisition continues its epoch.
 *
 * Times come from [clock], so they are the calling node's: keep the TTL well above the nodes' clock skew. JDBC calls
 * run on [dispatcher], virtual threads by default (decision D29).
 */
@InternalWowApi
class PostgresLeaseStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    /** How the tables are managed: created when missing by default (review A5-10). */
    private val ddl: StoreDdl = StoreDdl.AUTO,
) : LeaseStore {
    /** Claims the tables' format ([PostgresFormat], decision D59) and creates them when missing. */
    fun createSchema() {
        dataSource.autoCommitted { connection ->
            PostgresFormat.schema(connection, ddl, listOf(LEASES to FORMAT, MEMBERS_TABLE to FORMAT)) {
                connection.createStatement().use { statement -> DDL.forEach { statement.execute(it) } }
            }
        }
    }

    override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
        val now = clock.millis()
        return single(ACQUIRE, ::leaseOf) {
            setString(1, group)
            setInt(2, partition)
            setString(3, owner)
            setLong(4, now + ttl.inWholeMilliseconds)
            setLong(5, now)
            setLong(6, now)
        }
    }

    override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
        val now = clock.millis()
        return single(RENEW, ::leaseOf) {
            setLong(1, now + ttl.inWholeMilliseconds)
            setString(2, group)
            setInt(3, partition)
            setString(4, owner)
            setLong(5, now)
        }
    }

    override suspend fun release(group: String, partition: Int, owner: String): Boolean = execute(RELEASE) {
        setString(1, group)
        setInt(2, partition)
        setString(3, owner)
        setLong(4, clock.millis())
        executeUpdate() == 1
    }

    override suspend fun owners(group: String): List<Lease> = execute(OWNERS) {
        setString(1, group)
        setLong(2, clock.millis())
        executeQuery().use { rows -> rows.map(::leaseOf) }
    }

    override suspend fun heartbeat(group: String, node: String, address: String, ttl: Duration): Member {
        val expiresAt = clock.millis() + ttl.inWholeMilliseconds
        return execute(HEARTBEAT) {
            setString(1, group)
            setString(2, node)
            setString(3, address)
            setLong(4, expiresAt)
            executeUpdate()
            Member(group, node, address, expiresAt)
        }
    }

    override suspend fun members(group: String): List<Member> = execute(MEMBERS) {
        setString(1, group)
        setLong(2, clock.millis())
        executeQuery().use { rows -> rows.map(::memberOf) }
    }

    override suspend fun leave(group: String, node: String) {
        execute(LEAVE) {
            setString(1, group)
            setString(2, node)
            executeUpdate()
        }
    }

    private suspend fun <T> execute(sql: String, block: PreparedStatement.() -> T): T = dispatcher.jdbc {
        dataSource.autoCommitted { connection: Connection -> connection.prepareStatement(sql).use { it.block() } }
    }

    private suspend fun <T> single(sql: String, row: (ResultSet) -> T, bind: PreparedStatement.() -> Unit): T? =
        execute(sql) {
            bind()
            executeQuery().use { rows -> if (rows.next()) row(rows) else null }
        }

    private fun leaseOf(row: ResultSet) = Lease(
        group = row.getString("subscription_group"),
        partition = row.getInt("partition"),
        owner = row.getString("owner"),
        epoch = row.getLong("epoch"),
        expiresAt = row.getLong("expires_at"),
    )

    private fun memberOf(row: ResultSet) = Member(
        group = row.getString("subscription_group"),
        node = row.getString("node"),
        address = row.getString("address"),
        expiresAt = row.getLong("expires_at"),
    )

    private fun <T> ResultSet.map(row: (ResultSet) -> T): List<T> {
        val values = ArrayList<T>()
        while (next()) {
            values += row(this)
        }
        return values
    }

    private companion object {
        const val LEASES = "wow_lease"
        const val MEMBERS_TABLE = "wow_lease_member"

        /** The layout version of both tables (finding A2-13). */
        const val FORMAT = 1
        const val LEASE_COLUMNS = "subscription_group, partition, owner, epoch, expires_at"

        val DDL = listOf(
            """
            CREATE TABLE IF NOT EXISTS $LEASES (
                subscription_group text NOT NULL,
                partition integer NOT NULL,
                owner text NOT NULL,
                epoch bigint NOT NULL,
                expires_at bigint NOT NULL,
                PRIMARY KEY (subscription_group, partition)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS $MEMBERS_TABLE (
                subscription_group text NOT NULL,
                node text NOT NULL,
                address text NOT NULL,
                expires_at bigint NOT NULL,
                PRIMARY KEY (subscription_group, node)
            )
            """.trimIndent(),
        )

        /**
         * Inserts a first lease at epoch 1, or takes over a stored one that has expired (parameter 6) or is the
         * acquirer's. The epoch stays when the acquirer still holds it live (parameter 5) and is one more otherwise.
         */
        val ACQUIRE = """
            INSERT INTO $LEASES AS l ($LEASE_COLUMNS) VALUES (?, ?, ?, 1, ?)
            ON CONFLICT (subscription_group, partition) DO UPDATE SET
                owner = excluded.owner,
                epoch = CASE WHEN l.owner = excluded.owner AND l.expires_at > ? THEN l.epoch ELSE l.epoch + 1 END,
                expires_at = excluded.expires_at
            WHERE l.expires_at <= ? OR l.owner = excluded.owner
            RETURNING $LEASE_COLUMNS
        """.trimIndent()

        val RENEW = """
            UPDATE $LEASES SET expires_at = ?
            WHERE subscription_group = ? AND partition = ? AND owner = ? AND expires_at > ?
            RETURNING $LEASE_COLUMNS
        """.trimIndent()

        const val RELEASE = "UPDATE $LEASES SET expires_at = 0 " +
            "WHERE subscription_group = ? AND partition = ? AND owner = ? AND expires_at > ?"

        const val OWNERS = "SELECT $LEASE_COLUMNS FROM $LEASES " +
            "WHERE subscription_group = ? AND expires_at > ? ORDER BY partition"

        const val HEARTBEAT = "INSERT INTO $MEMBERS_TABLE (subscription_group, node, address, expires_at) " +
            "VALUES (?, ?, ?, ?) ON CONFLICT (subscription_group, node) " +
            "DO UPDATE SET address = excluded.address, expires_at = excluded.expires_at"

        const val MEMBERS = "SELECT subscription_group, node, address, expires_at FROM $MEMBERS_TABLE " +
            "WHERE subscription_group = ? AND expires_at > ? ORDER BY node COLLATE \"C\""

        const val LEAVE = "DELETE FROM $MEMBERS_TABLE WHERE subscription_group = ? AND node = ?"
    }
}
