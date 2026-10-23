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

package me.ahoo.wow.benchmark.load

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Runs [command] and gives back its standard output, or null when it fails or takes longer than [timeoutSeconds]. */
fun execute(vararg command: String, timeoutSeconds: Long = 30): String? = try {
    val process = ProcessBuilder(
        *command
    ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
} catch (_: java.io.IOException) {
    null
}

/**
 * The cost of a run, captured beside it over its measured phase (decision D224):
 *
 * - each server JVM ([processes], name to pid): its CPU time and resident memory every second (`/proc` on Linux, `ps`
 *   elsewhere), its garbage collector and heap from `jstat -gc`, one sampling process per JVM, and the bytes it
 *   allocated ([Allocations]), so the results give bytes per operation beside CPU;
 * - each container ([containers]): its CPU and memory from one `docker stats` stream;
 * - the stores' operation counters: MongoDB's `serverStatus().opcounters` ([mongoContainer]) and PostgreSQL's
 *   `pg_stat_database` of [postgresDatabase] with `pg_stat_wal` ([postgresContainer]), each from one session that
 *   prints them every second, and the end offsets of every Kafka topic ([kafkaContainer]).
 *
 * Nothing starts inside the measured phase (B.O-2): [prepare], at the warm-up, starts every sampling process, opens
 * the allocation connections and reads the Kafka offsets once (`kafka-get-offsets` is a JVM of its own); [start] and
 * [stop] only read what is already running, and [complete], after the drain, reads the Kafka offsets again. The
 * stores' counters at the phase's edges are the samples nearest to them; PostgreSQL's also give a per-second series of
 * commits and WAL writes and flushes (B.O-3). The samplers are a few percent of a core; the results say what they were.
 */
class Resources(
    private val processes: Map<String, Long>,
    private val containers: List<String>,
    private val mongoContainer: String?,
    private val postgresContainer: String?,
    private val postgresDatabase: String,
    private val kafkaContainer: String?,
) {
    private val psSamples = mutableListOf<Map<String, Any>>()
    private val gcLines = processes.keys.associateWith { mutableListOf<Pair<Long, String>>() }
    private val dockerLines = mutableListOf<Pair<Long, String>>()
    private val mongoSamples = mutableListOf<Pair<Long, Map<String, Double>>>()
    private val postgresSamples = mutableListOf<Pair<Long, Map<String, Double>>>()
    private val samplers = mutableListOf<Process>()
    private var allocations: Map<String, Allocations.Reader?> = emptyMap()
    private var psThread: Thread? = null
    private var preparedAt = 0L
    private var readyAt = 0L
    private var startedAt = 0L
    private var stoppedAt = 0L
    private var kafkaBefore: Pair<Long, Map<String, Long>?>? = null

    @Volatile
    private var sampling = false
    private var loadCpuBefore = 0.0
    private var cpuBefore: Map<String, Double> = emptyMap()
    private var allocatedBefore: Map<String, Long?> = emptyMap()

    /** What [stop] captured, once it has; [complete] adds the Kafka offsets. */
    val stopped = AtomicReference<Map<String, Any?>>()

    /**
     * Starts every sampling process and opens the allocation connections, at the warm-up: the first attach starts a
     * JVM's management agent, and `mongosh`, `psql` and `jstat` take a moment to start, none of which may land in the
     * measured phase. The Kafka offsets are read last, once.
     */
    fun prepare() {
        preparedAt = System.currentTimeMillis()
        processes.forEach { (name, pid) ->
            stream(listOf(JSTAT, "-gc", "-t", pid.toString(), SAMPLE_MILLIS.toString())) { line ->
                synchronized(gcLines) { gcLines.getValue(name) += System.currentTimeMillis() to line }
            }
        }
        if (containers.isNotEmpty()) {
            val format = "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}"
            stream(listOf("docker", "stats", "--format", format) + containers) { line ->
                synchronized(dockerLines) { dockerLines += System.currentTimeMillis() to line }
            }
        }
        mongoContainer?.let { container ->
            stream(listOf("docker", "exec", container, "mongosh", "--quiet", "--eval", MONGO_LOOP)) { line ->
                Samples.columns(
                    line,
                    MONGO_COLUMNS,
                    ' '
                )?.let { synchronized(mongoSamples) { mongoSamples += Samples.at(it) to it } }
            }
        }
        postgresContainer?.let { container ->
            val command =
                listOf("docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", postgresDatabase, "-XqtA")
            stream(
                command,
                input = "${postgresQuery(postgresDatabase)}\n\\watch ${SAMPLE_MILLIS / MILLIS_PER_SECOND}\n"
            ) { line ->
                Samples.columns(line, POSTGRES_COLUMNS, '|')?.let {
                    synchronized(postgresSamples) { postgresSamples += Samples.at(it) to it }
                }
            }
        }
        allocations = processes.mapValues { (_, pid) -> Allocations.connect(pid) }
        readyAt = System.currentTimeMillis()
        kafkaBefore = kafkaContainer?.let { System.currentTimeMillis() to kafkaOffsets(it) }
    }

    /** The measured phase starts: reads what [prepare] started, and launches nothing. */
    fun start() {
        allocatedBefore = allocations.mapValues { (_, reader) -> reader?.read() }
        startedAt = System.currentTimeMillis()
        loadCpuBefore = ownCpuSeconds()
        cpuBefore = cpuSeconds()
        sampling = true
        psThread = thread(name = "cpu-sampler", isDaemon = true) {
            while (sampling) {
                val at = System.currentTimeMillis() - startedAt
                cpu().forEach { (name, sample) -> synchronized(psSamples) { psSamples += sample + ("process" to name) + ("t" to at) } }
                Thread.sleep(SAMPLE_MILLIS)
            }
        }
    }

    /** The measured phase ends: gives back what the samplers captured over it, for the results. */
    fun stop(): Map<String, Any?> {
        sampling = false
        stoppedAt = System.currentTimeMillis()
        val seconds = (stoppedAt - startedAt) / MILLIS_PER_SECOND.toDouble()
        val cpuAfter = cpu().mapValues { (it.value["cpuSeconds"] as Number).toDouble() }
        val loadCpu = ownCpuSeconds() - loadCpuBefore
        val allocatedAfter = allocations.mapValues { (_, reader) -> reader?.read() }
        psThread?.join()
        // One more sample of each store after the edge, then the sampling processes stop.
        Thread.sleep(SAMPLE_MILLIS + SAMPLE_MILLIS / 2)
        samplers.forEach { it.destroy() }
        allocations.values.forEach { it?.close() }
        val window = startedAt..stoppedAt
        return mapOf(
            "seconds" to seconds,
            "processes" to processes.mapValues { (name, pid) ->
                val samples = synchronized(psSamples) { psSamples.filter { it["process"] == name } }
                val cpu = (cpuAfter[name] ?: 0.0) - (cpuBefore[name] ?: 0.0)
                val allocated = allocatedBefore[name]?.let { before -> allocatedAfter[name]?.minus(before) }
                mapOf(
                    "pid" to pid,
                    "cpuSeconds" to cpu,
                    "averageCores" to cpu / seconds,
                    // Null when either read failed (the JVM refused the attach or was gone).
                    "allocatedBytes" to allocated,
                    "rssMaxMb" to (samples.maxOfOrNull { it["rssMb"] as Double } ?: 0.0),
                    "samples" to samples.map { mapOf("t" to it["t"], "cpuSeconds" to it["cpuSeconds"], "rssMb" to it["rssMb"]) },
                    "gc" to gc(synchronized(gcLines) { Samples.within(gcLines.getValue(name).toList(), window) }),
                )
            },
            // The load generator's own CPU, to tell a run it could not drive from one the servers could not keep up with.
            "loadGenerator" to mapOf(
                "cpuSeconds" to loadCpu,
                "averageCores" to loadCpu / seconds,
                "availableProcessors" to Runtime.getRuntime().availableProcessors(),
            ),
            "containers" to containers(synchronized(dockerLines) { Samples.within(dockerLines.toList(), window) }),
            "stores" to stores(window),
            "postgresSeries" to synchronized(postgresSamples) {
                Samples.perSecond(postgresSamples.toList(), window, POSTGRES_SERIES)
            },
            // Whether every sampler was up before the measured phase began, and how long before.
            "samplersReadyBeforeMeasureSeconds" to (startedAt - readyAt) / MILLIS_PER_SECOND.toDouble(),
            "samplers" to "${if (procAvailable) "/proc" else "ps"} every ${SAMPLE_MILLIS}ms; jstat -gc -t <pid> $SAMPLE_MILLIS per JVM; " +
                "one docker stats stream; one mongosh and one psql session sampling every ${SAMPLE_MILLIS}ms; " +
                "allocations over JMX connections opened at the warm-up; all started at the warm-up",
        )
    }

    /** After the drain: the Kafka offsets again (a JVM of its own, so outside the measured phase), added to [stopped]. */
    fun complete() {
        val stoppedMap = stopped.get() ?: return
        val before = kafkaBefore ?: return
        val container = kafkaContainer ?: return
        val afterAt = System.currentTimeMillis()
        val after = kafkaOffsets(container)
        val stores = (stoppedMap["stores"] as? Map<*, *>)?.mapKeys { it.key.toString() }.orEmpty()
        val kafka = mapOf(
            // The offsets were read at the warm-up and after the drain: the delta covers more than the measured phase.
            "fromSecond" to (before.first - startedAt) / MILLIS_PER_SECOND.toDouble(),
            "toSecond" to (afterAt - startedAt) / MILLIS_PER_SECOND.toDouble(),
            "before" to before.second,
            "after" to after,
            "delta" to before.second?.let { b -> after?.mapValues { (topic, offset) -> offset - (b[topic] ?: 0L) } },
        )
        stopped.set(stoppedMap + ("stores" to stores + ("kafkaEndOffsets" to kafka)))
    }

    private fun stores(window: LongRange): Map<String, Any?> = buildMap {
        fun edges(samples: List<Pair<Long, Map<String, Double>>>): Map<String, Any?>? {
            val before = Samples.atOrBefore(samples, window.first) ?: return null
            val after = Samples.atOrAfter(samples, window.last) ?: return null
            return mapOf(
                "fromSecond" to (before.first - window.first) / MILLIS_PER_SECOND.toDouble(),
                "toSecond" to (after.first - window.first) / MILLIS_PER_SECOND.toDouble(),
                "before" to before.second,
                "after" to after.second,
                "delta" to after.second.mapValues { (key, value) -> Samples.whole(value - (before.second[key] ?: 0.0)) },
            )
        }
        if (mongoContainer != null) put("mongo", edges(synchronized(mongoSamples) { mongoSamples.toList() }))
        if (postgresContainer != null) {
            put(
                "postgres",
                edges(synchronized(postgresSamples) { postgresSamples.toList() })
            )
        }
    }

    private fun kafkaOffsets(container: String): Map<String, Long>? =
        execute("docker", "exec", container, "kafka-get-offsets", "--bootstrap-server", "localhost:9092")
            ?.lines()
            ?.filter { it.count { c -> c == ':' } >= 2 }
            ?.groupBy(
                { it.substringBeforeLast(':').substringBeforeLast(':') }
            ) { it.substringAfterLast(':').toLongOrNull() ?: 0L }
            ?.mapValues { it.value.sum() }
            ?.toSortedMap()

    private fun stream(command: List<String>, input: String? = null, onLine: (String) -> Unit) {
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: java.io.IOException) {
            return
        }
        if (input != null) {
            // The input stays open: psql repeats its \watch until it is stopped.
            process.outputStream.write(input.toByteArray())
            process.outputStream.flush()
        }
        samplers += process
        thread(name = "sampler-${command.first()}", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            } catch (_: java.io.IOException) {
                // The sampler was stopped: its output closed under the reader.
            }
        }
    }

    /** This JVM's CPU time so far, in seconds. */
    private fun ownCpuSeconds(): Double =
        ProcessHandle.current().info().totalCpuDuration().map { it.toNanos() / NANOS_PER_SECOND }.orElse(0.0)

    private val procAvailable = File("/proc/self/stat").exists()
    private val clockTicks by lazy { execute("getconf", "CLK_TCK")?.trim()?.toDoubleOrNull() ?: DEFAULT_CLOCK_TICKS }
    private val pageKb by lazy { (execute("getconf", "PAGESIZE")?.trim()?.toDoubleOrNull() ?: DEFAULT_PAGE_BYTES) / KB }

    /** Every process's cumulative CPU seconds, read as [cpu] reads them. */
    fun cpuSeconds(): Map<String, Double> = cpu().mapValues { (it.value["cpuSeconds"] as Number).toDouble() }

    /** Every process's cumulative CPU time and resident memory: `/proc` on Linux (no process started), else `ps`. */
    private fun cpu(): Map<String, Map<String, Any>> {
        if (processes.isEmpty()) return emptyMap()
        if (procAvailable) {
            return processes.mapNotNull { (name, pid) ->
                val stat = try {
                    File("/proc/$pid/stat").readText()
                } catch (_: java.io.IOException) {
                    return@mapNotNull null
                }
                Samples.procStat(stat, clockTicks, pageKb)?.let { name to it }
            }.toMap()
        }
        val output = execute("ps", "-o", "pid=,rss=,time=", "-p", processes.values.joinToString(",")) ?: return emptyMap()
        val byPid = output.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < PS_FIELDS) return@mapNotNull null
            parts[0].toLong() to mapOf("rssMb" to parts[1].toDouble() / KB_PER_MB, "cpuSeconds" to cpuSeconds(parts[2]))
        }.toMap()
        return processes.mapNotNull { (name, pid) -> byPid[pid]?.let { name to it } }.toMap()
    }

    /** `[[dd-]hh:]mm:ss[.ss]` as seconds. */
    private fun cpuSeconds(time: String): Double {
        val (days, clock) = if ('-' in time) time.substringBefore('-').toDouble() to time.substringAfter('-') else 0.0 to time
        return clock.split(':').fold(0.0) { total, part -> total * SIXTY + part.toDouble() } + days * SECONDS_PER_DAY
    }

    /**
     * The first and last `jstat -gc -t` sample of a JVM in the measured phase, and the most heap used: the collections
     * and their time during the phase are the difference of the counters (`YGC`, `YGCT`, `FGC`, `FGCT`, `CGC`, `CGCT`,
     * `GCT`). jstat prints its header once, at its start in the warm-up.
     */
    private fun gc(lines: List<String>): Map<String, Any?> {
        val header = synchronized(gcLines) {
            gcLines.values.asSequence().flatten().map { it.second }.firstOrNull { it.trim().startsWith("Timestamp") }
        }?.trim()?.split(Regex("\\s+")) ?: return mapOf("samples" to 0)
        val rows = lines.filterNot { it.trim().startsWith("Timestamp") || it.isBlank() }.mapNotNull { line ->
            val values = line.trim().split(Regex("\\s+"))
            if (values.size != header.size) null else header.zip(values).toMap()
        }
        if (rows.isEmpty()) return mapOf("samples" to 0)
        fun Map<String, String>.number(key: String): Double = this[key]?.toDoubleOrNull() ?: 0.0
        val heapUsed = rows.map { row -> HEAP_USED.sumOf { row.number(it) } / KB_PER_MB }
        val first = rows.first()
        val last = rows.last()
        return mapOf(
            "samples" to rows.size,
            "heapUsedMaxMb" to heapUsed.max(),
            "heapUsedAverageMb" to heapUsed.average(),
            "heapCapacityMb" to HEAP_CAPACITY.sumOf { last.number(it) } / KB_PER_MB,
            "delta" to GC_COUNTERS.associateWith { last.number(it) - first.number(it) },
        )
    }

    /** The `docker stats` lines per container in the measured phase: CPU percentages and memory. */
    private fun containers(lines: List<String>): Map<String, Any?> {
        val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val byName = lines.mapNotNull { raw ->
            val parts = raw.replace(ansi, "").split('\t')
            if (parts.size < DOCKER_FIELDS - 1) return@mapNotNull null
            val cpu = parts[1].removeSuffix("%").toDoubleOrNull() ?: return@mapNotNull null
            parts[0].trim() to mapOf("cpuPercent" to cpu, "memory" to parts[2], "netIO" to parts[3], "blockIO" to parts[4])
        }.groupBy({ it.first }, { it.second })
        return byName.mapValues { (_, samples) ->
            val cpu = samples.map { it["cpuPercent"] as Double }
            mapOf(
                "samples" to samples.size,
                "cpuPercentAverage" to cpu.average(),
                "cpuPercentMax" to cpu.max(),
                "lastMemory" to samples.last()["memory"],
                "lastNetIO" to samples.last()["netIO"],
                "lastBlockIO" to samples.last()["blockIO"],
            )
        }
    }

    companion object {
        const val SAMPLE_MILLIS = 1_000L

        /** The `jstat` of the JDK the load generator runs on, the servers' JDK in the harness. */
        private val JSTAT = File(System.getProperty("java.home"), "bin/jstat").path
        private const val MILLIS_PER_SECOND = 1_000L
        private const val KB_PER_MB = 1_024.0
        private const val KB = 1_024.0
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val SIXTY = 60.0
        private const val SECONDS_PER_DAY = 86_400.0
        private const val PS_FIELDS = 3
        private const val DOCKER_FIELDS = 6
        private const val DEFAULT_CLOCK_TICKS = 100.0
        private const val DEFAULT_PAGE_BYTES = 4_096.0
        private val HEAP_USED = listOf("S0U", "S1U", "EU", "OU")
        private val HEAP_CAPACITY = listOf("S0C", "S1C", "EC", "OC")
        private val GC_COUNTERS = listOf("YGC", "YGCT", "FGC", "FGCT", "CGC", "CGCT", "GCT")

        /** MongoDB's operation counters once a second, as `<epoch> <insert> <query> ...`. */
        private val MONGO_COLUMNS = listOf("epoch", "insert", "query", "update", "delete", "getmore", "command")
        private const val MONGO_LOOP =
            "while (true) { const o = db.serverStatus().opcounters; " +
                "print([Date.now() / 1000, o.insert, o.query, o.update, o.delete, o.getmore, o.command].join(' ')); sleep(1000); }"

        /** PostgreSQL's database counters and WAL statistics (`track_wal_io_timing` gives the times), once a second. */
        private val POSTGRES_COLUMNS = listOf(
            "epoch", "xact_commit", "xact_rollback", "tup_inserted", "tup_updated", "tup_deleted", "tup_fetched",
            "tup_returned", "blks_read", "blks_hit", "wal_records", "wal_fpi", "wal_bytes", "wal_buffers_full", "wal_write",
            "wal_sync", "wal_write_time", "wal_sync_time",
        )

        /** The per-second series kept of PostgreSQL's samples: commits, and WAL records, bytes, writes and flushes. */
        private val POSTGRES_SERIES = listOf(
            "xact_commit",
            "wal_records",
            "wal_bytes",
            "wal_write",
            "wal_sync",
            "wal_write_time",
            "wal_sync_time",
        )

        private fun postgresQuery(database: String): String =
            "select extract(epoch from clock_timestamp()), d.xact_commit, d.xact_rollback, d.tup_inserted, d.tup_updated, " +
                "d.tup_deleted, d.tup_fetched, d.tup_returned, d.blks_read, d.blks_hit, w.wal_records, w.wal_fpi, " +
                "w.wal_bytes, w.wal_buffers_full, w.wal_write, w.wal_sync, w.wal_write_time, w.wal_sync_time " +
                "from pg_stat_database d, pg_stat_wal w where d.datname = '$database';"

        /** Where the run happened (decision D224: machine, commit, JVM), as far as the machine tells. */
        fun environment(repository: File?): Map<String, Any?> = mapOf(
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            "cpu" to (execute("sysctl", "-n", "machdep.cpu.brand_string")?.trim() ?: execute("uname", "-m")?.trim()),
            "cores" to Runtime.getRuntime().availableProcessors(),
            "memoryGb" to execute("sysctl", "-n", "hw.memsize")?.trim()?.toLongOrNull()?.let { it / (1L shl 30) },
            "osVersion" to execute("sw_vers", "-productVersion")?.trim(),
            "loadGeneratorJvm" to "${System.getProperty("java.vm.vendor")} ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
            "docker" to execute("docker", "version", "--format", "{{.Server.Version}}")?.trim(),
            "dockerCpus" to execute("docker", "info", "--format", "{{.NCPU}}")?.trim(),
            "dockerMemory" to execute("docker", "info", "--format", "{{.MemTotal}}")?.trim()?.toLongOrNull()?.let {
                "${it / (1L shl 20)} MiB"
            },
            "commit" to repository?.let { execute("git", "-C", it.path, "rev-parse", "HEAD")?.trim() },
            "dirty" to repository?.let { execute("git", "-C", it.path, "status", "--porcelain")?.isNotBlank() },
        )
    }
}
