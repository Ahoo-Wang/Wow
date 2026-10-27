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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.wow.example.eshop.e2e.json
import me.ahoo.wow.example.eshop.e2e.soak.SoakDatabase
import tools.jackson.databind.JsonNode
import java.sql.DriverManager

/**
 * What the verdict and the probes read from an eShop context's store itself, on either arm: its checkpoints, the
 * leases of its partitions, its failure ledger, its log's integrity and its snapshots' lag, and a commit's request
 * facts. The context is the eShop's (`ordering`, `basket`, …), its database `eshop_<context>`.
 */
interface StoreReads {
    /** The checkpoints: `group|subscription|partition` to position. */
    fun checkpoints(context: String): Map<String, Long>

    /** The leases: `group|partition` to its owner and when it expires (epoch milliseconds). */
    fun leases(context: String): Map<String, Pair<String, Long>>

    /** The failure ledger's entries, each as evidence. */
    fun parked(context: String): List<Map<String, Any?>>

    /**
     * The log's integrity violations: an aggregate whose versions are not 1..max once each, and, as each backend can
     * tell, a partition whose sequences are not taken once each.
     */
    fun logViolations(context: String): List<Map<String, Any?>>

    /** The aggregates whose snapshot is missing or not at their last logged version. */
    fun snapshotsBehind(context: String): List<Map<String, Any?>>

    /** The headers (request facts) of the last commit of [aggregate] [id], or a null node while there is none. */
    fun lastHeaders(context: String, aggregate: String, id: String): JsonNode

    companion object {
        /** The reads of the arm [backend]: PostgreSQL through its node port, MongoDB through mongosh in its pod. */
        fun of(backend: String, kube: Kube, postgres: String): StoreReads =
            if (backend == "mongo") MongoReads(kube) else PostgresReads(postgres)
    }
}

/** The postgres arm's reads, through PostgreSQL's node port with the soak's queries ([SoakDatabase]). */
class PostgresReads(private val url: String) : StoreReads {
    private fun <T> database(context: String, block: (SoakDatabase) -> T): T =
        DriverManager.getConnection(
            "$url/eshop_$context",
            "postgres",
            "postgres"
        ).use { block(SoakDatabase(it, context)) }

    override fun checkpoints(context: String): Map<String, Long> = database(context) { it.checkpoints() }

    override fun leases(context: String): Map<String, Pair<String, Long>> = database(context) { it.leases() }

    override fun parked(context: String): List<Map<String, Any?>> = database(context) { it.parked().values.toList() }

    override fun logViolations(context: String): List<Map<String, Any?>> = database(context) { database ->
        val tables = database.logTables().values
        database.partitionGaps(tables) + tables.flatMap(database::versionGaps)
    }

    override fun snapshotsBehind(context: String): List<Map<String, Any?>> = database(context) { database ->
        database.logTables().values.flatMap(database::snapshotsBehind)
    }

    override fun lastHeaders(context: String, aggregate: String, id: String): JsonNode =
        DriverManager.getConnection("$url/eshop_$context", "postgres", "postgres").use { connection ->
            val table = SoakDatabase(connection, context).logTables().getValue(aggregate)
            connection.prepareStatement(
                "SELECT headers FROM \"$table\" WHERE aggregate_id = ? ORDER BY version DESC LIMIT 1"
            )
                .use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            json.readTree(
                                rows.getString(1)
                            )
                        } else {
                            json.nullNode()
                        }
                    }
                }
        }
}

/**
 * The mongo arm's reads, with mongosh inside a member's pod against the replica set, its answers as relaxed
 * Extended JSON. The collections are named as PostgreSQL's tables (`wow_log__eshop_<context>__<aggregate>`, the
 * registry `wow_log_aggregate`, `wow_checkpoint`, `wow_lease`, `wow_failure_ledger`, `wow_snapshot__…`).
 *
 * The log's integrity: an aggregate's versions 1..max once each, and no sequence of a partition taken twice across
 * the context's collections. MongoDB's log reserves sequences in blocks and records the ones no stream took as
 * placeholders (decision D271), so a sequence missing between two that are taken is a block still open, not a loss:
 * unlike PostgreSQL's dense counter, it is not counted as a violation.
 */
class MongoReads(private val kube: Kube) : StoreReads {
    private fun eval(context: String, script: String): JsonNode {
        val full = "const db = connect('$URI').getSiblingDB('eshop_$context'); " +
            "print(EJSON.stringify((() => { $script })(), { relaxed: true }))"
        val result = kube.exec(POD, "mongosh", "--quiet", "--nodb", "--eval", full)
        check(result.ok) { "mongosh on eshop_$context failed: ${result.err.take(ERROR)}${result.out.take(ERROR)}" }
        return json.readTree(result.out.trim().lineSequence().last())
    }

    override fun checkpoints(context: String): Map<String, Long> = eval(
        context,
        "return db.wow_checkpoint.find({}, { _id: 0, subscription_group: 1, subscription: 1, partition: 1, position: 1 }).toArray()",
    ).values().associate {
        "${it["subscription_group"].asString()}|${it["subscription"].asString()}|${it["partition"].asInt()}" to
            it["position"].asLong()
    }

    override fun leases(context: String): Map<String, Pair<String, Long>> = eval(
        context,
        "return db.wow_lease.find({}, { _id: 0, subscription_group: 1, partition: 1, owner: 1, expires_at: 1 }).toArray()",
    ).values().associate {
        "${it["subscription_group"].asString()}|${it["partition"].asInt()}" to
            (it["owner"]?.asString().orEmpty() to millis(it["expires_at"]))
    }

    override fun parked(context: String): List<Map<String, Any?>> = eval(
        context,
        "return db.wow_failure_ledger.find({}, { _id: 0, subscription: 1, context: 1, aggregate_name: 1, aggregate_id: 1, " +
            "from_version: 1, reason: 1 }).toArray()",
    ).values().map { entry ->
        linkedMapOf<String, Any?>(
            "database" to context,
            "subscription" to entry["subscription"]?.asString(),
            "aggregate" to "${entry["context"]?.asString()}.${entry["aggregate_name"]?.asString()}",
            "aggregateId" to entry["aggregate_id"]?.asString(),
            "fromVersion" to entry["from_version"]?.asInt(),
            "reason" to entry["reason"]?.asString()?.take(ERROR),
        )
    }

    override fun logViolations(context: String): List<Map<String, Any?>> = eval(
        context,
        """
        const collections = db.wow_log_aggregate.find({ context: 'eshop-$context' }).toArray().map(r => r._id);
        const out = [];
        for (const c of collections) {
          db.getCollection(c).aggregate([
            { ${'$'}match: { aggregate_id: { ${'$'}exists: true } } },
            { ${'$'}group: { _id: { t: '${'$'}tenant_id', a: '${'$'}aggregate_id' }, n: { ${'$'}sum: 1 },
              d: { ${'$'}addToSet: '${'$'}version' }, min: { ${'$'}min: '${'$'}version' }, max: { ${'$'}max: '${'$'}version' } } },
            { ${'$'}match: { ${'$'}expr: { ${'$'}or: [ { ${'$'}ne: ['${'$'}n', '${'$'}max'] }, { ${'$'}ne: ['${'$'}min', 1] },
              { ${'$'}ne: [{ ${'$'}size: '${'$'}d' }, '${'$'}n'] } ] } } }
          ]).forEach(g => out.push({ kind: 'versions', collection: c, aggregateId: g._id.a, versions: g.n, min: g.min, max: g.max }));
        }
        if (collections.length > 0) {
          const [first, ...rest] = collections;
          db.getCollection(first).aggregate([
            ...rest.map(c => ({ ${'$'}unionWith: c })),
            { ${'$'}group: { _id: { p: '${'$'}partition', s: '${'$'}sequence' }, n: { ${'$'}sum: 1 } } },
            { ${'$'}match: { n: { ${'$'}gt: 1 } } }
          ], { allowDiskUse: true }).forEach(g => out.push({ kind: 'sequence taken twice', partition: g._id.p, sequence: g._id.s, times: g.n }));
        }
        return out;
        """.trimIndent(),
    ).values().map { node -> node.properties().associate { (key, value) -> key to value.toString() } }

    override fun snapshotsBehind(context: String): List<Map<String, Any?>> = eval(
        context,
        """
        const out = [];
        for (const r of db.wow_log_aggregate.find({ context: 'eshop-$context' }).toArray()) {
          const snapshots = r._id.replace(/^wow_log__/, 'wow_snapshot__');
          db.getCollection(r._id).aggregate([
            { ${'$'}match: { aggregate_id: { ${'$'}exists: true } } },
            { ${'$'}group: { _id: { t: '${'$'}tenant_id', a: '${'$'}aggregate_id' }, v: { ${'$'}max: '${'$'}version' } } },
            { ${'$'}lookup: { from: snapshots, let: { t: '${'$'}_id.t', a: '${'$'}_id.a' }, as: 's', pipeline: [
              { ${'$'}match: { ${'$'}expr: { ${'$'}and: [ { ${'$'}eq: ['${'$'}tenant_id', '${'$'}${'$'}t'] },
                { ${'$'}eq: ['${'$'}aggregate_id', '${'$'}${'$'}a'] } ] } } }, { ${'$'}project: { version: 1 } } ] } },
            { ${'$'}match: { ${'$'}expr: { ${'$'}ne: [{ ${'$'}first: '${'$'}s.version' }, '${'$'}v'] } } }
          ]).forEach(g => out.push({ collection: snapshots, aggregateId: g._id.a, logVersion: g.v,
            snapshotVersion: g.s.length ? g.s[0].version : null }));
        }
        return out;
        """.trimIndent(),
    ).values().map { node -> node.properties().associate { (key, value) -> key to value.toString() } }

    override fun lastHeaders(context: String, aggregate: String, id: String): JsonNode = eval(
        context,
        "const d = db.getCollection('wow_log__eshop_${context}__$aggregate').find({ aggregate_id: '$id' })" +
            ".sort({ version: -1 }).limit(1).toArray(); return d.length ? d[0].headers : null",
    )

    /** An expiry as relaxed Extended JSON writes it: a number of milliseconds, or a `{"${'$'}date": …}`. */
    private fun millis(node: JsonNode?): Long = when {
        node == null || node.isNull -> 0
        node.isNumber -> node.asLong()
        node.has("\$date") -> node["\$date"].let { date ->
            if (date.isNumber) date.asLong() else java.time.Instant.parse(date.asString()).toEpochMilli()
        }
        else -> 0
    }

    private companion object {
        const val POD = "mongo-0"
        const val URI = "mongodb://mongo-0.mongo:27017,mongo-1.mongo:27017,mongo-2.mongo:27017/?replicaSet=rs0"
        const val ERROR = 500
    }
}
