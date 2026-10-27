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

package me.ahoo.wow.analytics.catalog.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetArtifact
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetState
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.spi.json.WowJson
import org.springframework.context.SmartLifecycle
import java.time.Clock
import java.time.Duration

/**
 * The data plane's view of the datasets' publications (-2): every node of the
 * service runs one, and keeps its [datasets] (the datasets its queries serve) in step with the published versions,
 * read from the datasets' snapshots as trusted code, never on a query's path.
 *
 * - Every [interval] it reads the datasets whose snapshot changed since the newest change it saw (less a margin for
 *   the clocks of the nodes that commit them), and every [reconcileInterval] all of them, which also catches a
 *   dataset that is gone. A version published is queryable on every node within [interval] and one read.
 * - A dataset that serves a version is put under its name, its version's view as its table; one retired is
 *   removed; one that needs revision is put [unavailable][QueryDataset.unavailable], its queries answered
 *   `wow.query.dataset-unavailable`, and why is logged. A dataset of the same name from a dataset file takes precedence: it is left in place.
 * - A failed read is logged and tried again at the next one; the datasets keep the versions they have, so queries
 *   go on while the catalog's store is down. [synced] says whether one read ever succeeded (the node's readiness),
 *   [lastSuccess] when the last one did, [lag] how long the newest applied publication took to be applied.
 *
 * A node-local subscription of the datasets' state stream would do this without reading the snapshots: 10.x.
 */
@OptIn(ExperimentalWowApi::class)
class DatasetPublicationWatcher(
    private val queries: () -> QueryApi,
    private val datasets: QueryDatasets,
    private val interval: Duration,
    private val reconcileInterval: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : SmartLifecycle {
    private val applied = HashMap<AggregateType, Int>()
    private val files: Set<AggregateType> = datasets.all.map { it.name }.toSet()
    private var watermark = 0L
    private var lastFull = 0L
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    @Volatile
    var lastSuccess: Long = 0L
        private set

    @Volatile
    var lag: Duration = Duration.ZERO
        private set

    val synced: Boolean get() = lastSuccess > 0

    /** Seconds since the last read succeeded, NaN before the first. */
    fun syncAge(): Double = if (synced) (clock.millis() - lastSuccess) / MILLIS else Double.NaN

    /** Reads the datasets changed since the last read, or all of them when a reconcile is due; the changes applied. */
    suspend fun sync(): Int {
        val now = clock.millis()
        val full = lastFull == 0L || now - lastFull >= reconcileInterval.toMillis()
        val since = watermark - CLOCK_MARGIN.toMillis()
        // A job that reads the whole model, not a request: without the in-process budget's list bound.
        val rows = queries().trusted().unbounded().snapshots<DatasetState>().list(
            listQuery {
                if (!full) {
                    filter { "lastEventTime" gte since }
                }
                sort { "lastEventTime".asc() }
            },
        ).toList()
        var changes = rows.count { apply(it, now, measured = !full) }
        if (full) {
            val listed = rows.map { nameOf(it.state) }.toSet()
            applied.keys.filter { it !in listed }.forEach { name ->
                applied.remove(name)
                if (datasets.remove(name)) changes++
            }
            lastFull = now
        }
        rows.maxOfOrNull { it.lastEventTime }?.let { watermark = maxOf(watermark, it) }
        lastSuccess = now
        return changes
    }

    /**
     * Follows [row]: puts the version it serves, or removes it; whether that changed what this node serves. A change
     * an incremental read applies is [measured]: [lag] is how long after its commit it is served.
     */
    private fun apply(row: Snapshot<DatasetState>, now: Long, measured: Boolean): Boolean {
        val state = row.state
        val name = nameOf(state)
        if (name in files) {
            logger.warn { "$name comes from a dataset file, which takes precedence over its publication." }
            return false
        }
        // Needing revision, a dataset whose published version no longer holds is served unavailable.
        val revision = state.status == DatasetStatus.NEEDS_REVISION && state.serving() == null
        val serving = state.serving() ?: state.published()?.artifact?.takeIf { revision }
        // A dataset that needs revision is served unavailable: a version of its own, never one a publication has.
        val version = serving?.version?.let { if (revision) -it else it }
        return when {
            serving == null && name in applied -> {
                applied.remove(name)
                datasets.remove(name)
                true
            }

            serving == null || applied[name] == version -> false
            revision -> {
                datasets.put(datasetOf(serving).withUnavailable(NEEDS_REVISION))
                applied[name] = checkNotNull(version)
                logger.warn { "$name needs revision, its queries refused: ${state.problems}" }
                true
            }

            else -> {
                datasets.put(datasetOf(serving))
                applied[name] = serving.version
                if (measured) lag = Duration.ofMillis((now - row.lastEventTime).coerceAtLeast(0))
                logger.info { "$name serves version ${serving.version} from ${serving.database}.${serving.view}." }
                true
            }
        }
    }

    override fun start() {
        val started = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = started
        job = started.launch {
            while (isActive) {
                try {
                    sync()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("TooGenericExceptionCaught") // Any failure of a read is tried again at the next.
                    failure: Exception,
                ) {
                    logger.warn(failure) { "The datasets' publications could not be read; tried again in $interval." }
                }
                delay(interval.toMillis())
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        job = null
    }

    override fun isRunning(): Boolean = job?.isActive == true

    companion object {
        /** Why a dataset that needs revision is unavailable, as its queries are answered: fixed words. */
        const val NEEDS_REVISION: String = "needs revision"

        /** How far before the newest change seen a read starts: the clocks of the committing nodes may differ. */
        @JvmField
        val CLOCK_MARGIN: Duration = Duration.ofSeconds(30)

        private const val MILLIS = 1000.0

        private val logger = KotlinLogging.logger {}

        private fun nameOf(state: DatasetState) = AggregateType(state.context, state.name)

        /** The dataset a query node serves for [artifact]: its version's view, its scope, its fields' protection. */
        fun datasetOf(artifact: DatasetArtifact): QueryDataset =
            QueryDataset.of(AggregateType(artifact.context, artifact.name)) {
                val scope = artifact.scope
                scope.tenant?.let { tenant(it) } ?: platform()
                scope.owner?.let { owner(it) }
                scope.space?.let { space(it) }
                artifact.identity?.let { identity(it) }
                artifact.eventTime?.let { eventTime(it) }
                artifact.title?.let { title(it) }
                storage(WowJson.mapper.createObjectNode().put("table", "${artifact.database}.${artifact.view}"))
                val types = artifact.columns.associate { it.name to it.type }
                (listOfNotNull(scope.tenant, scope.owner, scope.space) + artifact.fields.map { it.name }).forEach { name ->
                    val column = artifact.fields.firstOrNull { it.name == name }
                    field(name) {
                        types[name]?.let { ClickHouseDatasetColumns.declare(this, it) }
                        column?.title?.let { title(it) }
                        column?.reference?.let { reference(it.context, it.aggregate) }
                    }
                    val sensitivity = column?.sensitivity ?: return@forEach
                    val level = sensitivity.level ?: return@forEach
                    sensitive(name, level, QueryMask.Keep.of(sensitivity.keepPrefix, sensitivity.keepSuffix))
                }
            }
    }
}
