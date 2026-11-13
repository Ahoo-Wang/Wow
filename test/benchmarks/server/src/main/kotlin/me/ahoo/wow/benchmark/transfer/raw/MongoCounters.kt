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

package me.ahoo.wow.benchmark.transfer.raw

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoDatabase
import org.bson.Document

/** MongoDB's counters and settings around a raw run's windows, read with [admin] on [database]. */
class MongoCounters(private val admin: MongoClient, private val database: MongoDatabase) {
    /**
     * MongoDB's cumulative counters: the journal's syncs, their time, writes and bytes; the oplog's bytes; the
     * operations; and the snapshot collection's write latency (`latencyStats`, microseconds) and its writes.
     */
    fun counters(): Map<String, Double> {
        val status = database.runCommand(Document("serverStatus", 1))
        val journal = (status["wiredTiger"] as Document?)?.get("log") as Document?
        val ops = status["opcounters"] as Document?
        val oplog = admin.getDatabase("local").runCommand(Document("collStats", "oplog.rs"))
        val latency = database.getCollection(RawMongo.RAW_SNAPSHOT)
            .aggregate(listOf(Document("\$collStats", Document("latencyStats", Document()))))
            .firstOrNull()?.let { (it["latencyStats"] as Document?)?.get("writes") as Document? }
        fun number(document: Document?, key: String): Double? = (document?.get(key) as Number?)?.toDouble()
        return buildMap {
            number(journal, "log sync operations")?.let { put("journalSyncs", it) }
            number(journal, "log sync time duration (usecs)")?.let { put("journalSyncMs", it / MICROS_PER_MILLI) }
            number(journal, "log write operations")?.let { put("journalWrites", it) }
            number(journal, "log bytes written")?.let { put("journalBytes", it) }
            number(oplog, "size")?.let { put("oplogBytes", it) }
            number(ops, "insert")?.let { put("inserts", it) }
            number(ops, "update")?.let { put("updates", it) }
            number(ops, "command")?.let { put("commands", it) }
            number(latency, "latency")?.let { put("snapshotWriteMicros", it) }
            number(latency, "ops")?.let { put("snapshotWrites", it) }
        }
    }

    /**
     * Each of Wow's and the clone's collections' inserts, updates and removes (the `top` command): where a
     * calibration's gap is.
     */
    fun tables(): Map<String, Double> {
        val totals = admin.getDatabase("admin").runCommand(Document("top", 1))["totals"] as Document?
            ?: return emptyMap()
        return buildMap {
            for ((namespace, value) in totals) {
                if (!namespace.contains(".wow_") && !namespace.contains(".raw_")) continue
                val counts = value as Document
                for (kind in listOf("insert", "update", "remove", "queries", "commands")) {
                    val count = (counts[kind] as Document?)?.get("count") as Number? ?: continue
                    put("$namespace $kind", count.toDouble())
                }
            }
        }
    }

    /** The mean latency of the snapshot collection's writes over a step, microseconds, from the counters' growth. */
    fun ratios(growth: Map<String, Double>): Map<String, Double> {
        val writes = growth["snapshotWrites"] ?: 0.0
        if (writes <= 0) return emptyMap()
        return mapOf("snapshotWriteMicros" to (growth["snapshotWriteMicros"] ?: 0.0) / writes)
    }

    /**
     * The server's version and the settings a ceiling is quoted with: its storage engine, journal and write
     * concern.
     */
    fun settings(): Map<String, Any?> {
        val build = database.runCommand(Document("buildInfo", 1))
        val status = database.runCommand(Document("serverStatus", 1))
        val adminDatabase = admin.getDatabase("admin")
        val options = adminDatabase.runCommand(Document("getCmdLineOpts", 1))
        val replica = runCatching { adminDatabase.runCommand(Document("replSetGetConfig", 1)) }.getOrNull()
        val config = replica?.get("config") as Document?
        return mapOf(
            "version" to build.getString("version"),
            "storageEngine" to (status["storageEngine"] as Document?)?.getString("name"),
            "writeConcern" to "majority",
            "journal" to ((options["parsed"] as Document?)?.get("storage") as Document?)?.get("journal")?.toString(),
            "writeConcernMajorityJournalDefault" to config?.get("writeConcernMajorityJournalDefault"),
            "replicaSetMembers" to (config?.get("members") as List<*>?)?.size,
        )
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000.0
    }
}
