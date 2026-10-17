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
 * - each server JVM ([processes], name to pid): its CPU time and resident memory from `ps` every second, and its
 *   garbage collector and heap from `jstat -gc`, one sampling process per JVM;
 * - each container ([containers]): its CPU and memory from one `docker stats` stream;
 * - the stores' operation counters before and after: MongoDB's `serverStatus().opcounters` ([mongoContainer]),
 *   PostgreSQL's `pg_stat_database` of [postgresDatabase] ([postgresContainer]) and the end offsets of every Kafka topic
 *   ([kafkaContainer]), whose difference is what the measured phase wrote and read.
 *
 * The samplers are processes of their own, a few percent of a core; the results say what they were.
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
    private val gcLines = processes.keys.associateWith { mutableListOf<String>() }
    private val dockerLines = mutableListOf<String>()
    private val samplers = mutableListOf<Process>()
    private var psThread: Thread? = null
    private var startedAt = 0L

    @Volatile
    private var sampling = false
    private var storesBefore: Map<String, Any?> = emptyMap()
    private var cpuBefore: Map<String, Double> = emptyMap()

    /** What [stop] captured, once it has. */
    val stopped = AtomicReference<Map<String, Any?>>()

    /** Starts the samplers: the CPU times first, then the stores' counters, which take a moment to read. */
    fun start() {
        startedAt = System.currentTimeMillis()
        cpuBefore = ps().mapValues { (it.value["cpuSeconds"] as Number).toDouble() }
        sampling = true
        psThread = thread(name = "ps-sampler", isDaemon = true) {
            while (sampling) {
                val at = System.currentTimeMillis() - startedAt
                ps().forEach { (name, sample) -> synchronized(psSamples) { psSamples += sample + ("process" to name) + ("t" to at) } }
                Thread.sleep(SAMPLE_MILLIS)
            }
        }
        processes.forEach { (name, pid) ->
            stream(listOf(JSTAT, "-gc", "-t", pid.toString(), SAMPLE_MILLIS.toString())) { line ->
                synchronized(gcLines) { gcLines.getValue(name) += line }
            }
        }
        if (containers.isNotEmpty()) {
            val format = "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}"
            stream(listOf("docker", "stats", "--format", format) + containers) { line ->
                synchronized(dockerLines) { dockerLines += "${System.currentTimeMillis() - startedAt}\t$line" }
            }
        }
        storesBefore = storeCounters()
    }

    /** Stops the samplers and gives back what they captured, for the results. */
    fun stop(): Map<String, Any?> {
        sampling = false
        val seconds = (System.currentTimeMillis() - startedAt) / MILLIS_PER_SECOND.toDouble()
        val cpuAfter = ps().mapValues { (it.value["cpuSeconds"] as Number).toDouble() }
        samplers.forEach { it.destroy() }
        psThread?.join()
        val storesAfter = storeCounters()
        return mapOf(
            "seconds" to seconds,
            "processes" to processes.mapValues { (name, pid) ->
                val samples = synchronized(psSamples) { psSamples.filter { it["process"] == name } }
                val cpu = (cpuAfter[name] ?: 0.0) - (cpuBefore[name] ?: 0.0)
                mapOf(
                    "pid" to pid,
                    "cpuSeconds" to cpu,
                    "averageCores" to cpu / seconds,
                    "rssMaxMb" to (samples.maxOfOrNull { it["rssMb"] as Double } ?: 0.0),
                    "samples" to samples.map { mapOf("t" to it["t"], "cpuSeconds" to it["cpuSeconds"], "rssMb" to it["rssMb"]) },
                    "gc" to gc(synchronized(gcLines) { gcLines.getValue(name).toList() }),
                )
            },
            "containers" to containers(synchronized(dockerLines) { dockerLines.toList() }),
            "stores" to mapOf("before" to storesBefore, "after" to storesAfter, "delta" to delta(storesBefore, storesAfter)),
            "samplers" to "ps every ${SAMPLE_MILLIS}ms; jstat -gc -t <pid> $SAMPLE_MILLIS per JVM; one docker stats stream",
        )
    }

    private fun stream(command: List<String>, onLine: (String) -> Unit) {
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: java.io.IOException) {
            return
        }
        samplers += process
        thread(name = "sampler-${command.first()}", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        }
    }

    /** `ps` of every process: its cumulative CPU time and resident memory. */
    private fun ps(): Map<String, Map<String, Any>> {
        if (processes.isEmpty()) return emptyMap()
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
     * The first and last `jstat -gc -t` sample of a JVM, and the most heap used: the collections and their time during
     * the run are the difference of the counters (`YGC`, `YGCT`, `FGC`, `FGCT`, `CGC`, `CGCT`, `GCT`).
     */
    private fun gc(lines: List<String>): Map<String, Any?> {
        val header = lines.firstOrNull {
            it.trim().startsWith("Timestamp")
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

    /** The `docker stats` lines per container: CPU percentages and memory. */
    private fun containers(lines: List<String>): Map<String, Any?> {
        val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val byName = lines.mapNotNull { raw ->
            val parts = raw.replace(ansi, "").split('\t')
            if (parts.size < DOCKER_FIELDS) return@mapNotNull null
            val cpu = parts[2].removeSuffix("%").toDoubleOrNull() ?: return@mapNotNull null
            parts[1].trim() to mapOf("t" to parts[0].toLong(), "cpuPercent" to cpu, "memory" to parts[3], "netIO" to parts[4], "blockIO" to parts[5])
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

    private fun storeCounters(): Map<String, Any?> = buildMap {
        mongoContainer?.let { container ->
            val output = execute(
                "docker",
                "exec",
                container,
                "mongosh",
                "--quiet",
                "--eval",
                "const o = db.serverStatus().opcounters; print(['insert','query','update','delete','getmore','command'].map(k => k + '=' + o[k]).join(' '))",
            )
            put(
                "mongo",
                output?.trim()?.split(
                    ' '
                )?.associate { it.substringBefore('=') to it.substringAfter('=').toLongOrNull() }
            )
        }
        postgresContainer?.let { container ->
            val columns = listOf(
                "xact_commit",
                "tup_inserted",
                "tup_updated",
                "tup_deleted",
                "tup_fetched",
                "tup_returned",
                "blks_read",
                "blks_hit"
            )
            val output = execute(
                "docker", "exec", container, "psql", "-U", "postgres", "-d", postgresDatabase, "-tAc",
                "select ${columns.joinToString(",")} from pg_stat_database where datname = '$postgresDatabase'",
            )
            put(
                "postgres",
                output?.trim()?.split('|')?.let { values -> columns.zip(values.map { it.toLongOrNull() }).toMap() }
            )
        }
        kafkaContainer?.let { container ->
            val output = execute(
                "docker",
                "exec",
                container,
                "kafka-get-offsets",
                "--bootstrap-server",
                "localhost:9092"
            )
            put(
                "kafkaEndOffsets",
                output?.lines()?.filter {
                    it.count { c -> c == ':' } >= 2
                }?.groupBy({ it.substringBeforeLast(':').substringBeforeLast(':') }) {
                    it.substringAfterLast(':').toLongOrNull() ?: 0L
                }?.mapValues { it.value.sum() }?.toSortedMap(),
            )
        }
    }

    private fun delta(before: Map<String, Any?>, after: Map<String, Any?>): Map<String, Any?> = after.mapValues { (store, values) ->
        val earlier = before[store] as? Map<*, *> ?: return@mapValues null
        (values as? Map<*, *>)?.entries?.associate { (key, value) ->
            key.toString() to ((value as? Long ?: 0L) - (earlier[key] as? Long ?: 0L))
        }
    }

    companion object {
        const val SAMPLE_MILLIS = 1_000L

        /** The `jstat` of the JDK the load generator runs on, the servers' JDK in the harness. */
        private val JSTAT = File(System.getProperty("java.home"), "bin/jstat").path
        private const val MILLIS_PER_SECOND = 1_000L
        private const val KB_PER_MB = 1_024.0
        private const val SIXTY = 60.0
        private const val SECONDS_PER_DAY = 86_400.0
        private const val PS_FIELDS = 3
        private const val DOCKER_FIELDS = 6
        private val HEAP_USED = listOf("S0U", "S1U", "EU", "OU")
        private val HEAP_CAPACITY = listOf("S0C", "S1C", "EC", "OC")
        private val GC_COUNTERS = listOf("YGC", "YGCT", "FGC", "FGCT", "CGC", "CGCT", "GCT")

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
