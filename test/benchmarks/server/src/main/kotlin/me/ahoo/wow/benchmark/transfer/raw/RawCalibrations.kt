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

import me.ahoo.wow.benchmark.transfer.CONNECTION_HOLD
import me.ahoo.wow.benchmark.transfer.POOL_WAIT
import kotlin.math.roundToInt

// The calibration's checks: what of Wow's own run on the store the clone's calibration step is held to, per store.

/**
 * PostgreSQL's calibration of Wow's own write path (WowShape) paced to Wow's rate in Wow's shape ([calibrating]): on
 * the WAL of the cloned tables when both runs read it by table (PATH_CHECKS), else on all of it (POSTGRES_CHECKS); its
 * calls of Wow's sizes (EQUAL_SHAPE_CHECKS), its saves kept up and no failed command either way. Apart, the model's own
 * shape at that rate ([uncapped]) against Wow's, and their calls' times: Wow's node time around a call.
 */
internal fun postgresCalibration(wow: Map<String, Double>, calibrating: RawStep, uncapped: RawStep): RawCalibration {
    val wowPath = PostgresCounters.onTables(wow, RawPostgres.WOW_TABLES, PATH)
    val rawPath = PostgresCounters.onTables(calibrating.perUnit, RawPostgres.RAW_TABLES, PATH)
    val path = wowPath.isNotEmpty() && rawPath.isNotEmpty()
    val spread = spreadOf(wow)?.let { mapOf(WowShape.PARTITIONS_PER_APPEND to it) }.orEmpty()
    return calibrationOf(
        (if (path) PATH_CHECKS else POSTGRES_CHECKS) + EQUAL_SHAPE_CHECKS + KEPT_CHECKS,
        (if (path) listOf("walRecords", "walBytes") else emptyList()) + listOf("walFpi", "commits") + XMIN_APART +
            WowShape.PARTITIONS_PER_APPEND + SHAPE_APART + CALL_APART + PostgresCounters.NEW_PAGE_SHARE + LAYOUT_METRICS,
        (postgresWow(wow) + modelCounts(wow, PG_APPENDS, PG_SAVES) + wowPath + spread + wowCalls(wow)).let { it + shapeOf(it) },
        calibrating,
        modelled(calibrating) + rawPath + uncappedOf(uncapped),
    )
}

/**
 * Wow's partitions an append in its own run: its partitions table's row updates per command (one a partition an
 * append takes streams of, its counter moved) over its appends per command; from the WAL's update records when they
 * were read, else from the table's counters. Null without its appends.
 */
internal fun spreadOf(wow: Map<String, Double>): Double? {
    val appends = wow[PG_APPENDS]?.takeIf { it > 0 } ?: return null
    val records = UPDATE_RECORDS.mapNotNull { wow["wal $it ${RawPostgres.PARTITIONS} records"] }
    val updates = if (records.isNotEmpty()) records.sum() else wow["${RawPostgres.PARTITIONS} updates"] ?: return null
    return updates / appends
}

/**
 * Wow's append calls from its queue's counts over its window (WowWindow), where it has no clock of its own: a call's
 * mean time, the appenders over the calls a second, when the lanes' groups waited for them throughout (a mean of one
 * at least: both appenders always in a call); a group's mean wait, by Little's law, the groups waiting over the groups
 * handed a second (the calls a second times the partitions an append, one group a partition).
 */
private fun wowCalls(wow: Map<String, Double>): Map<String, Double> {
    val calls = wow[WowWindow.APPEND_CALLS_PER_SECOND_ALL]?.takeIf { it > 0 } ?: return emptyMap()
    val waiting = wow[WowWindow.HANDED_GROUPS] ?: return emptyMap()
    return buildMap {
        if (waiting >= 1) put("${WowShape.APPEND_CALL}Ms", WowShape.APPENDERS * MILLIS / calls)
        spreadOf(wow)?.let { put("${WowShape.APPEND_GAP}Ms", waiting / (calls * it) * MILLIS) }
    }
}

/** The calls' times reported apart on both sides (CallTimes, Wow's from wowTimes and wowCalls). */
private val CALL_APART: List<String>
    get() = WOW_TIMES + listOf("${WowShape.APPEND_CALL}Ms", "${WowShape.APPEND_GAP}Ms", "${WowShape.SAVE_CALL}Ms")
private const val MILLIS = 1_000.0

/** The WAL's records of a row's update: HOT, not, and onto a new page. */
private val UPDATE_RECORDS = listOf("Heap/HOT_UPDATE", "Heap/UPDATE", "Heap/UPDATE+INIT")

/** Wow's PostgreSQL counters per command as the calibration reads them: with its snapshot table's HOT share. */
private fun postgresWow(wow: Map<String, Double>): Map<String, Double> {
    val table = RawPostgres.SNAPSHOT
    val hot = (wow["$table updates"] ?: 0.0).takeIf { it > 0 }?.let {
        mapOf(
            PostgresCounters.HOT_SHARE to (wow["$table hot updates"] ?: 0.0) / it,
            PostgresCounters.NEW_PAGE_SHARE to (wow["$table new page updates"] ?: 0.0) / it,
        )
    }.orEmpty()
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

/** Wow's append and save calls per command, from its statements or commands ([append], [save]), and its saves kept. */
private fun modelCounts(wow: Map<String, Double>, append: String, save: String): Map<String, Double> = buildMap {
    wow[append]?.let { put(APPENDS, it) }
    wow[save]?.let { put(SAVES, it) }
    // Wow's run lets its snapshots land before its counters are read: every append's snapshot saved.
    put(WowShape.SAVES_PER_APPEND, 1.0)
}

/** The model's append and save calls per command and its failed snapshots per command, from its step's counters. */
private fun modelled(calibrating: RawStep): Map<String, Double> = mapOf(
    APPENDS to (calibrating.perUnit[WowShape.SHAPE_APPEND_CALLS] ?: 0.0),
    SAVES to (calibrating.perUnit[WowShape.SHAPE_SAVE_CALLS] ?: 0.0),
    WOW_FAILED to (calibrating.perUnit[WowShape.SHAPE_SAVE_FAILED] ?: 0.0),
)

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

/**
 * MongoDB's calibration of Wow's own write path (WowShape) paced to Wow's rate: its inserts, updates, journal and
 * oplog bytes, the model's calls, its saves kept up and no failed command.
 */
internal fun mongoCalibration(wow: Map<String, Double>, calibrating: RawStep, uncapped: RawStep): RawCalibration =
    calibrationOf(
        MONGO_CHECKS + EQUAL_SHAPE_CHECKS + KEPT_CHECKS,
        listOf("commands", OWN_OPLOG_BYTES) + SHAPE_APART,
        (mongoWow(wow) + modelCounts(wow, MONGO_APPENDS, MONGO_SAVES)).let { it + shapeOf(it) },
        calibrating,
        modelled(calibrating) + uncappedOf(uncapped),
    )

/** Wow's commands a second in its own run, the rate the calibration is paced at. */
internal const val WOW_RATE = "wowCommandsPerSecond"
private const val SHARE = 0.10

/** The store counters' prefix of a statement's count among Wow's counters. */
internal const val STATEMENTS = "statements: "

/** Wow's run's store calls' times, as the model's are named: round trips, a pool's wait, a connection's hold. */
internal val WOW_TIMES = listOf(
    "${RawPostgres.APPEND_ROUND_TRIP}Ms",
    "${RawPostgres.SAVE_ROUND_TRIP}Ms",
    "${POOL_WAIT}Ms",
    "${CONNECTION_HOLD}Ms",
    APPEND_HOLD_MS,
    SAVE_HOLD_MS,
)

/** Wow's run's connection holds of its appends and its saves alone, taken to closed (StoreCounter.timings). */
internal const val APPEND_HOLD_MS = "appendConnectionHoldMs"
internal const val SAVE_HOLD_MS = "saveConnectionHoldMs"
internal const val PG_APPENDS = "statements: statement with wow_log__transfer__account"
internal const val PG_SAVES = "statements: statement set wow_snapshot__transfer__account"
internal const val MONGO_APPENDS = "commands: insert wow_log__transfer__account"
internal const val MONGO_SAVES = "commands: update wow_snapshot__transfer__account"
private const val APPENDS = "appendsPerCommand"
private const val SAVES = "savesPerCommand"
internal const val WOW_FAILED = "failedPerCommand"
internal const val OWN_OPLOG_BYTES = "ownOplogBytes"

/**
 * The horizon's diagnostics (PostgresCounters.watch), reported apart on both sides: a reading of the HOT share's gap
 * (open transactions holding back pruning) to test, not a check until it is confirmed.
 */
private val XMIN_APART = PostgresCounters.XMIN_METRICS

/**
 * The equal shape's checks: the calibration's append and save calls per command within 10% of Wow's, which its calls'
 * sizes (WowShape.Options.appendFill, saveFill) are set to, so its store counters are at Wow's shape.
 */
private val EQUAL_SHAPE_CHECKS =
    listOf(APPENDS to RawCheck.Bound(share = SHARE), SAVES to RawCheck.Bound(share = SHARE))

/**
 * The model's own shape at Wow's rate against Wow's, apart and never a check: its batches follow from its calls' time,
 * and Wow's from its node's time around each call too (CALL_APART), which the model does not have.
 */
private val SHAPE_APART = listOf(SHAPE_APPENDS, SHAPE_SAVES, SHAPE_SPREAD)

/** Wow's shape under SHAPE_APART's names: its append and save calls per command, its partitions an append. */
private fun shapeOf(wow: Map<String, Double>): Map<String, Double> = buildMap {
    wow[APPENDS]?.let { put(SHAPE_APPENDS, it) }
    wow[SAVES]?.let { put(SHAPE_SAVES, it) }
    wow[WowShape.PARTITIONS_PER_APPEND]?.let { put(SHAPE_SPREAD, it) }
}

/**
 * The uncapped model's step as the calibration reports it apart: its shape under SHAPE_APART's names, and its calls'
 * times (CALL_APART), the comparison of Wow's node time with none.
 */
private fun uncappedOf(uncapped: RawStep): Map<String, Double> = buildMap {
    putAll(uncapped.ratios.filterKeys { it.endsWith("Ms") })
    put(SHAPE_APPENDS, uncapped.perUnit[WowShape.SHAPE_APPEND_CALLS] ?: 0.0)
    put(SHAPE_SAVES, uncapped.perUnit[WowShape.SHAPE_SAVE_CALLS] ?: 0.0)
    uncapped.ratios[WowShape.PARTITIONS_PER_APPEND]?.let { put(SHAPE_SPREAD, it) }
}

private const val SHAPE_APPENDS = "uncapped appendsPerCommand"
private const val SHAPE_SAVES = "uncapped savesPerCommand"
private const val SHAPE_SPREAD = "uncapped partitionsPerAppend"

/**
 * Wow's run's call sizes: the commands an append took and the snapshots a save took, from its counts [append] and
 * [save] per command; null when its run did not count them.
 */
internal fun sizesOf(wow: Map<String, Double>, append: String, save: String): Pair<Int, Int>? {
    val appends = wow[append]?.takeIf { it > 0 } ?: return null
    val saves = wow[save]?.takeIf { it > 0 } ?: return null
    return (1 / appends).roundToInt().coerceAtLeast(1) to (1 / saves).roundToInt().coerceAtLeast(1)
}

/**
 * The model's saves kept up with its appends (WowShape.SAVES_KEPT, as a committed ceiling's), and no command of Wow's
 * run failed, nor a snapshot of the model's: a failed command wrote less than a deposit.
 */
private val KEPT_CHECKS = listOf(
    WowShape.SAVES_PER_APPEND to RawCheck.Bound(absolute = 1 - WowShape.SAVES_KEPT),
    WOW_FAILED to RawCheck.Bound(absolute = 0.0),
)

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
