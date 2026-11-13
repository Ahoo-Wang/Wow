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

import kotlin.math.roundToInt

// The calibration's checks: what of Wow's own run on the store the clone's calibration step is held to, per store.

/**
 * PostgreSQL's calibration: on the WAL of the cloned tables when both runs read it by table (PATH_CHECKS), else on all
 * of it (POSTGRES_CHECKS); the batch shape's checks and no failed command either way.
 */
internal fun postgresCalibration(
    wow: Map<String, Double>,
    calibrating: RawStep,
    logBatch: Int,
    snapshotBatch: Int,
): RawCalibration {
    val wowPath = PostgresCounters.onTables(wow, RawPostgres.WOW_TABLES, PATH)
    val rawPath = PostgresCounters.onTables(calibrating.perUnit, RawPostgres.RAW_TABLES, PATH)
    val path = wowPath.isNotEmpty() && rawPath.isNotEmpty()
    return calibrationOf(
        (if (path) PATH_CHECKS else POSTGRES_CHECKS) + shapeChecks(logBatch, snapshotBatch) + FAILED_CHECK,
        (if (path) listOf("walRecords", "walBytes") else emptyList()) + listOf("walFpi", "commits"),
        postgresWow(wow) + shapeCounts(wow, PG_APPENDS, PG_SAVES) + wowPath,
        calibrating,
        shaped(logBatch, snapshotBatch) + rawPath,
    )
}

/** Wow's PostgreSQL counters per command as the calibration reads them: with its snapshot table's HOT share. */
private fun postgresWow(wow: Map<String, Double>): Map<String, Double> {
    val table = RawPostgres.SNAPSHOT
    val hot = (wow["$table updates"] ?: 0.0).takeIf { it > 0 }
        ?.let { mapOf("hotShare" to (wow["$table hot updates"] ?: 0.0) / it) }.orEmpty()
    return wow + hot
}

/**
 * Wow's MongoDB counters per command as the calibration reads them: its updates without its node's own (checkpoints,
 * leases: their collections' `top` counts) and its oplog and journal bytes without the node's own oplog entries over
 * the window, which the clone does not write. The journal has no account by collection: it also records those writes'
 * documents, which stay in it, so its check leans to Wow's side by that much; the node's own oplog bytes, reported
 * apart, say about how much.
 */
private fun mongoWow(wow: Map<String, Double>): Map<String, Double> {
    val own = wow.filterKeys { it.endsWith(" update") && ".wow_" in it && "__" !in it }.values.sum()
    val ownBytes = wow[OWN_OPLOG_BYTES] ?: 0.0
    return wow + buildMap {
        wow["updates"]?.let { put("updates", it - own) }
        wow["oplogBytes"]?.let { put("oplogBytes", it - ownBytes) }
        wow["journalBytes"]?.let { put("journalBytes", it - ownBytes) }
    }
}

/** Wow's appends and saves per command, from the statements or commands [shapeOf] reads, as the shape checks name them. */
private fun shapeCounts(wow: Map<String, Double>, append: String, save: String): Map<String, Double> = buildMap {
    wow[append]?.let { put(APPENDS, it) }
    wow[save]?.let { put(SAVES, it) }
}

/** The clone's appends and saves per command in the calibration's shape: an append a flush, a save a snapshotBatch. */
private fun shaped(logBatch: Int, snapshotBatch: Int): Map<String, Double> = mapOf(
    APPENDS to 1.0 / logBatch,
    SAVES to 1.0 / snapshotBatch,
    WOW_FAILED to 0.0,
)

/**
 * Wow's batch shape in its own run, from its statements or commands per command: the commands an append took (the
 * count [append]) and the snapshots a save took (the count [save]), each its own: Wow's writer saves behind the appends
 * and across them, so a save may take more snapshots than an append's commands; null when its run did not count them.
 */
fun shapeOf(wow: Map<String, Double>, append: String, save: String): Pair<Int, Int>? {
    val appends = wow[append]?.takeIf { it > 0 } ?: return null
    val saves = wow[save]?.takeIf { it > 0 } ?: return null
    return (1 / appends).roundToInt().coerceAtLeast(1) to (1 / saves).roundToInt().coerceAtLeast(1)
}

/**
 * The calibration: each of [checks]' metrics per command of Wow's own run ([wow], every counter's) against the
 * [calibrating] step's, within its bound; [apart]'s metrics reported beside them, unbounded.
 */
fun calibrationOf(
    checks: List<Pair<String, RawCheck.Bound>>,
    apart: List<String>,
    wow: Map<String, Double>,
    calibrating: RawStep,
    shape: Map<String, Double> = emptyMap(),
): RawCalibration {
    val raw = calibrating.perUnit + calibrating.ratios + shape
    return RawCalibration(
        checks = checks.map { (metric, bound) -> RawCheck(metric, wow[metric], raw[metric], bound) },
        apart = apart.map { RawCheck(it, wow[it], raw[it], RawCheck.Bound()) },
        wowCounters = wow,
        rawCounters = raw,
        read = calibrating.read,
    )
}

/** MongoDB's calibration: its inserts, updates, journal and oplog bytes, the batch shape's checks and no failed command. */
internal fun mongoCalibration(
    wow: Map<String, Double>,
    calibrating: RawStep,
    logBatch: Int,
    snapshotBatch: Int,
): RawCalibration = calibrationOf(
    MONGO_CHECKS + shapeChecks(logBatch, snapshotBatch) + FAILED_CHECK,
    listOf("commands", OWN_OPLOG_BYTES),
    mongoWow(wow) + shapeCounts(wow, MONGO_APPENDS, MONGO_SAVES),
    calibrating,
    shaped(logBatch, snapshotBatch),
)

/** Wow's commands a second in its own run, the rate the calibration is paced at. */
internal const val WOW_RATE = "wowCommandsPerSecond"
private const val SHARE = 0.10
private const val HALF = 0.5
internal const val PG_APPENDS = "statements: statement with wow_log__transfer__account"
internal const val PG_SAVES = "statements: statement set wow_snapshot__transfer__account"
internal const val MONGO_APPENDS = "commands: insert wow_log__transfer__account"
internal const val MONGO_SAVES = "commands: update wow_snapshot__transfer__account"
private const val APPENDS = "appendsPerCommand"
private const val SAVES = "savesPerCommand"
internal const val WOW_FAILED = "failedPerCommand"
internal const val OWN_OPLOG_BYTES = "ownOplogBytes"

/**
 * The batch shape's checks: the appends and saves per command, each within what rounding Wow's to whole calls of
 * [logBatch] commands and [snapshotBatch] snapshots can come to (half a command an append, half a snapshot a save), so
 * a shape read from the wrong counts fails while Wow's own rounding does not.
 */
private fun shapeChecks(logBatch: Int, snapshotBatch: Int) = listOf(
    APPENDS to RawCheck.Bound(absolute = roundingOf(logBatch)),
    SAVES to RawCheck.Bound(absolute = roundingOf(snapshotBatch)),
)

/** How far 1 / [batch] can be from what rounded to [batch]: 1 / ([batch] - 1/2) - 1 / [batch]. */
private fun roundingOf(batch: Int) = 1.0 / (batch - HALF) - 1.0 / batch

/** No command of Wow's run failed: a failed command wrote less than a deposit. */
private val FAILED_CHECK = listOf(WOW_FAILED to RawCheck.Bound(absolute = 0.0))

/**
 * PostgreSQL's checks when the WAL's records were not read by table: all its records and bytes within 10%, the HOT
 * share within 0.10.
 */
private val POSTGRES_CHECKS = listOf(
    "walRecords" to RawCheck.Bound(share = SHARE),
    "walBytes" to RawCheck.Bound(share = SHARE),
    "hotShare" to RawCheck.Bound(absolute = SHARE),
)

/** The tables' prefix of the WAL on the cloned tables (PostgresCounters.onTables). */
private const val PATH = "path"

/**
 * PostgreSQL's checks with the WAL read by table (pg_walinspect): the records and bytes on the tables the clone writes
 * (the log, its partitions' counters and the snapshots, their indexes with them) within 10% of Wow's on its own, the
 * HOT share within 0.10. All of the WAL is reported apart: Wow's node also writes its checkpoints and leases, which
 * the clone does not, and they go with time rather than commands (more of them a command the slower the run), so no
 * bound on all of it holds at every rate; their records stay in the counters by table.
 */
private val PATH_CHECKS = listOf(
    "${PATH}WalRecords" to RawCheck.Bound(share = SHARE),
    "${PATH}WalBytes" to RawCheck.Bound(share = SHARE),
    "hotShare" to RawCheck.Bound(absolute = SHARE),
)

/** MongoDB's checks: inserts, updates, journal and oplog bytes, each within 10%. */
private val MONGO_CHECKS = listOf("inserts", "updates", "journalBytes", "oplogBytes")
    .map { it to RawCheck.Bound(share = SHARE) }
