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

package me.ahoo.wow.example.eshop.e2e.soak

import me.ahoo.wow.example.eshop.e2e.json
import tools.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.ResultSet

/**
 * What the soak reads from a context's PostgreSQL database, in the PostgreSQL backend's layout (format 1): the log
 * tables `wow_log__<context>__<aggregate>` the registry `wow_log_aggregate` lists, the partition counters
 * `wow_log__<context>__partition`, the snapshots `wow_snapshot__<context>__<aggregate>`, the checkpoints
 * `wow_checkpoint` and the failure ledger `wow_failure_ledger`. A table not created yet reads as empty.
 */
class SoakDatabase(private val connection: Connection, val context: String) {
    private val contextPart = "eshop_$context"

    /** The log table of each aggregate type of the context. */
    fun logTables(): Map<String, String> = query(
        "SELECT aggregate, log_table FROM wow_log_aggregate WHERE context = ?",
        "eshop-$context",
    ) { it.getString(1) to it.getString(2) }.toMap()

    /** The checkpoints: `group|subscription|partition` to position. */
    fun checkpoints(): Map<String, Long> = query(
        "SELECT subscription_group, subscription, partition, position FROM wow_checkpoint",
    ) { "${it.getString(1)}|${it.getString(2)}|${it.getInt(3)}" to it.getLong(4) }.toMap()

    /** The leases (`wow_lease`): `group|partition` to its owner and when it expires (epoch milliseconds, 0 released). */
    fun leases(): Map<String, Pair<String, Long>> = query(
        "SELECT subscription_group, partition, owner, expires_at FROM wow_lease",
    ) { "${it.getString(1)}|${it.getInt(2)}" to (it.getString(3) to it.getLong(4)) }.toMap()

    /** The parked entries, keyed by subscription and aggregate. */
    fun parked(): Map<String, Map<String, Any?>> = query(
        "SELECT subscription, context, aggregate_name, tenant_id, aggregate_id, from_version, reason, failed_at " +
            "FROM wow_failure_ledger",
    ) { row ->
        val key = "${row.getString(1)}|${row.getString(2)}.${row.getString(3)}|${row.getString(5)}"
        key to linkedMapOf<String, Any?>(
            "database" to context,
            "subscription" to row.getString(1),
            "aggregate" to "${row.getString(2)}.${row.getString(3)}",
            "aggregateId" to row.getString(5),
            "fromVersion" to row.getInt(6),
            "reason" to row.getString(7).take(REASON),
            "failedAt" to row.getLong(8),
        )
    }.toMap()

    /**
     * Each partition's head and what the log tables hold of it, in one statement and so one snapshot: a partition
     * whose entries are not exactly the sequences 1..head once each.
     */
    fun partitionGaps(tables: Collection<String>): List<Map<String, Any?>> {
        if (tables.isEmpty()) return emptyList()
        val union = tables.joinToString(" UNION ALL ") { "SELECT partition, sequence FROM \"$it\"" }
        val sql = "SELECT p.partition, p.sequence, coalesce(e.n, 0), coalesce(e.d, 0), coalesce(e.mn, 0), " +
            "coalesce(e.mx, 0) FROM \"wow_log__${contextPart}__partition\" p LEFT JOIN (SELECT partition, count(*) n, " +
            "count(DISTINCT sequence) d, min(sequence) mn, max(sequence) mx FROM ($union) u GROUP BY partition) e " +
            "ON e.partition = p.partition"
        return query(sql) { row ->
            val head = row.getLong(2)
            val count = row.getLong(3)
            val distinct = row.getLong(4)
            val min = row.getLong(5)
            val max = row.getLong(6)
            val contiguous = count == head && distinct == count && (count == 0L || (min == 1L && max == head))
            if (contiguous) {
                null
            } else {
                linkedMapOf<String, Any?>(
                    "context" to context,
                    "partition" to row.getInt(1),
                    "head" to head,
                    "entries" to count,
                    "distinct" to distinct,
                    "min" to min,
                    "max" to max,
                )
            }
        }.filterNotNull()
    }

    /** The aggregates of [table] whose versions are not exactly 1..max once each. */
    fun versionGaps(table: String): List<Map<String, Any?>> = query(
        "SELECT tenant_id, aggregate_id, count(*), min(version), max(version) FROM \"$table\" GROUP BY 1, 2 " +
            "HAVING count(*) <> max(version) OR min(version) <> 1 OR count(DISTINCT version) <> count(*)",
    ) { row ->
        linkedMapOf<String, Any?>(
            "table" to table,
            "aggregateId" to row.getString(2),
            "versions" to row.getLong(3),
            "min" to row.getInt(4),
            "max" to row.getInt(5),
        )
    }

    /** How many aggregates [table] holds. */
    fun aggregateCount(table: String): Long =
        query("SELECT count(DISTINCT (tenant_id, aggregate_id)) FROM \"$table\"") { it.getLong(1) }.single()

    /** The aggregates of the log [table] whose snapshot is missing or not at their last version. */
    fun snapshotsBehind(table: String): List<Map<String, Any?>> {
        val snapshots = table.replaceFirst("wow_log__", "wow_snapshot__")
        return query(
            "SELECT l.aggregate_id, l.v, s.version FROM (SELECT tenant_id, aggregate_id, max(version) v FROM \"$table\" " +
                "GROUP BY 1, 2) l LEFT JOIN \"$snapshots\" s ON s.tenant_id = l.tenant_id AND " +
                "s.aggregate_id = l.aggregate_id WHERE s.version IS DISTINCT FROM l.v",
        ) { row ->
            linkedMapOf<String, Any?>(
                "table" to snapshots,
                "aggregateId" to row.getString(1),
                "logVersion" to row.getInt(2),
                "snapshotVersion" to row.getObject(3),
            )
        }
    }

    /** The snapshot states of [table]'s aggregate type: aggregate id to its state. */
    fun snapshotStates(table: String): Map<String, JsonNode> =
        query("SELECT aggregate_id, state FROM \"${table.replaceFirst("wow_log__", "wow_snapshot__")}\"") { row ->
            row.getString(1) to json.readTree(row.getString(2))
        }.toMap()

    /** The owner (`owner_id`) of each aggregate of [table]'s snapshots, by id: an order's buyer. */
    fun snapshotOwners(table: String): Map<String, String?> =
        query("SELECT aggregate_id, owner_id FROM \"${table.replaceFirst("wow_log__", "wow_snapshot__")}\"") { row ->
            row.getString(1) to row.getString(2)
        }.toMap()

    /** Every commit of [table], by aggregate in version order. */
    fun commits(table: String): Map<String, List<Commit>> = query(
        "SELECT aggregate_id, version, request_id, events FROM \"$table\" ORDER BY aggregate_id, version",
    ) { row ->
        val events = json.readTree(row.getString(4)).values().map { event ->
            val name = event["name"].asString()
            Event(name.substringAfterLast('.'), event["payload"], system = name.startsWith(SYSTEM_PREFIX))
        }
        Commit(row.getString(1), row.getInt(2), row.getString(3), events)
    }.groupBy { it.aggregateId }

    /** The request ids of [table] that match [pattern] (`LIKE`), with how many times each is there. */
    fun requestIds(table: String, pattern: String): Map<String, Int> = query(
        "SELECT request_id, count(*) FROM \"$table\" WHERE request_id LIKE ? GROUP BY request_id",
        pattern,
    ) { it.getString(1) to it.getInt(2) }.toMap()

    private fun <T> query(sql: String, vararg parameters: Any, row: (ResultSet) -> T): List<T> {
        if (!tablesExist(sql)) return emptyList()
        return connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(row(rows)) }
            }
        }
    }

    /** Whether the tables [sql] reads from exist: a context's tables appear as its nodes first use them. */
    private fun tablesExist(sql: String): Boolean = TABLE.findAll(sql).map { it.groupValues[1] + it.groupValues[2] }
        .filter { it.startsWith("wow_") }
        .distinct()
        .all { table ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                statement.setString(1, "\"$table\"")
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }

    /** A commit of an aggregate: its version, its request id and its events. */
    data class Commit(val aggregateId: String, val version: Int, val requestId: String, val events: List<Event>)

    /** An event: its simple name, its payload, and whether it is a system event (`wow.ScheduleSet`, ...). */
    data class Event(val name: String, val payload: JsonNode, val system: Boolean = false)

    private companion object {
        const val REASON = 500

        /** The prefix of the system events the kernel commits beside the domain's (decision D198). */
        const val SYSTEM_PREFIX = "wow."
        val TABLE = Regex("""(?:FROM|JOIN)\s+(?:"([a-z0-9_]+)"|([a-z0-9_]+))""")
    }
}
