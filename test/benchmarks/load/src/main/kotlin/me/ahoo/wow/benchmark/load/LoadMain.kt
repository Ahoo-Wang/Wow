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
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The load generator of the end-to-end benchmark (decision D224; test/benchmarks/README.md):
 *
 * - `seed`: opens the accounts, each answered once its snapshot holds it;
 * - `check`: one operation of every scenario, each checked as a run checks it;
 * - `prime`: an untimed pass that touches every seeded account once, so a run on a large data set measures warm
 *   caches rather than filling them;
 * - `run`: one open-loop run of a scenario at a fixed rate, its results and resources written as JSON and Markdown;
 * - `saturate`: the closed-loop saturation finder.
 *
 * Options are `--name=value`; see [usage].
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: usage()
    val options = Options.parse(args.drop(1))
    val adapter = VersionAdapter.of(options.required("version"))
    val targets = options.required("targets").split(',').map { URI.create(it.trim().trimEnd('/')) }
    val executor = Executors.newFixedThreadPool(
        options.int("client-threads", Runtime.getRuntime().availableProcessors())
    )
    val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .executor(executor)
        .build()
    val client = Client(http, adapter)
    val status = when (command) {
        "seed" -> seed(client, targets, options)
        "check" -> check(client, targets, options)
        "prime" -> prime(client, targets, options)
        "run" -> run(client, targets, options)
        "saturate" -> saturate(client, targets, options)
        else -> usage()
    }
    executor.shutdownNow()
    exitProcess(status)
}

private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val DEFAULT_ACCOUNTS = 1_000
private const val DEFAULT_BALANCE = 1_000_000_000_000L
private const val DEFAULT_SEED_CONCURRENCY = 32
private const val PRIME_CONCURRENCY = 256
private const val DEFAULT_SEED = 20_261_015L
private const val PERCENT = 100.0
private const val MICROS_PER_MILLI = 1_000.0

private fun usage(): Nothing {
    System.err.println(
        """
        usage: benchmark-load <seed|check|run|saturate> --version=9x|v10 --targets=http://host:port[,…] [options]
          seed:     --accounts=1000 --concurrency=32 --balance=1000000000000
          prime:    --accounts=1000 --concurrency=256 --out=<dir>
          check:    --accounts=1000 [--scenario=… | --mix=…: only those]
          run:      --scenario=E1|E1-large|E2|E3|E4-state|E4-list (or --mix=E1:50,E4-state:40,E4-list:10) --rate=<ops/s>
                    --warm-up=30 --measure=60
                    --distribution=uniform|zipf|hot --zipf-exponent=1.1 --hot-fraction=0.5 --hot-keys=1
                    --accounts=1000 --seed=20261015 --max-in-flight=20000 --out=<dir> --label=<text>
                    --pids=name:pid,… --containers=name,… --mongo=<container> --postgres=<container>
                    --postgres-database=transfer --kafka=<container> --repository=<dir> --meta=key=value;…
          saturate: --scenario=… (or --mix=…) --start-concurrency=1 --max-concurrency=256 --warm-up=5 --step=10 --out=<dir>
        """.trimIndent(),
    )
    exitProcess(2)
}

/** Opens [Options] `accounts` accounts, at most `concurrency` at a time. */
private fun seed(client: Client, targets: List<URI>, options: Options): Int {
    val accounts = options.int("accounts", DEFAULT_ACCOUNTS)
    val balance = options.long("balance", DEFAULT_BALANCE)
    val permits = Semaphore(options.int("concurrency", DEFAULT_SEED_CONCURRENCY))
    val failures = AtomicInteger()
    val firstFailure = AtomicReference<String>()
    val started = System.nanoTime()
    val futures = (0 until accounts).map { index ->
        permits.acquire()
        val request = client.adapter.openAccount(
            targets[index % targets.size],
            accountId(index),
            ownerOf(index),
            balance
        )
        client.command(request).handle { error, failure ->
            val reason = error ?: failure?.let { OpenLoop.rootCause(it).toString() }
            if (reason != null) {
                failures.incrementAndGet()
                firstFailure.compareAndSet(null, "${accountId(index)}: $reason")
            }
            permits.release()
        }
    }
    CompletableFuture.allOf(*futures.toTypedArray()).join()
    val seconds = (System.nanoTime() - started) / OpenLoop.NANOS_PER_SECOND.toDouble()
    println("[seed] ${client.adapter.name}: $accounts accounts in ${"%.1f".format(seconds)}s, ${failures.get()} failed")
    firstFailure.get()?.let { println("[seed] first failure: $it") }
    return if (failures.get() == 0) 0 else 1
}

/**
 * The priming pass (D224 note ⑤): every seeded account once, `concurrency` at a time, closed loop and untimed. Each
 * account takes a deposit answered once stored, which brings its aggregate into the node's state cache and its log
 * and snapshot pages into the store's (the snapshot a read hits is written by that deposit). A data set above the
 * state cache's capacity
 * ends full, with evictions, which is the steady state such a run measures. Writes `prime.json` (the accounts, the
 * seconds, the failures) to `--out`.
 */
private fun prime(client: Client, targets: List<URI>, options: Options): Int {
    val accounts = options.int("accounts", DEFAULT_ACCOUNTS)
    val permits = Semaphore(options.int("concurrency", PRIME_CONCURRENCY))
    val failures = AtomicInteger()
    val started = System.nanoTime()
    val futures = (0 until accounts).map { index ->
        permits.acquire()
        val target = targets[index % targets.size]
        val id = accountId(index)
        client.command(client.adapter.deposit(target, id, 1, Wait.STORED))
            .handle { error, failure ->
                if (error != null || failure != null) failures.incrementAndGet()
                permits.release()
            }
    }
    CompletableFuture.allOf(*futures.toTypedArray()).join()
    val seconds = (System.nanoTime() - started) / OpenLoop.NANOS_PER_SECOND.toDouble()
    val took = "%.1f".format(seconds)
    println("[prime] ${client.adapter.name}: $accounts accounts in ${took}s, ${failures.get()} failed")
    options.optional("out")?.let {
        File(it).apply { mkdirs() }.resolve("prime.json").writeText(
            Json.write(mapOf("accounts" to accounts, "seconds" to seconds, "failed" to failures.get())) + "\n",
        )
    }
    return 0
}

/** Sends one operation of each scenario, as a run would, and says whether each reached its completion point. */
private fun check(client: Client, targets: List<URI>, options: Options): Int {
    val accounts = options.int("accounts", DEFAULT_ACCOUNTS)
    var failed = 0
    // The run's own scenarios when it names them (--mix, or a --scenario the generator knows), else every one the
    // version supports: a baseline older than a scenario still runs the others.
    val named = options.optional("mix")?.let(Mix::parse)?.parts?.map { it.first }
        ?: options.optional("scenario")?.let { label -> Scenario.entries.firstOrNull { it.label.equals(label, true) } }
            ?.let(::listOf)
    (named ?: Scenario.entries.filter(client.adapter::supports)).forEach { scenario ->
        val target = Target(targets.first(), 0, minOf(1, accounts - 1))
        val started = System.nanoTime()
        val error = try {
            scenario.run(client, target).join()
        } catch (failure: java.util.concurrent.CompletionException) {
            OpenLoop.rootCause(failure).toString()
        }
        val millis = (System.nanoTime() - started) / OpenLoop.NANOS_PER_SECOND.toDouble() * MICROS_PER_MILLI
        println("[check] ${client.adapter.name} ${scenario.label}: ${error ?: "ok"} (${"%.1f".format(millis)} ms)")
        if (error != null) failed++
    }
    return if (failed == 0) 0 else 1
}

private fun keyChooser(options: Options, seed: Long): KeyChooser = KeyChooser(
    distribution = options.string("distribution", KeyChooser.UNIFORM),
    accounts = options.int("accounts", DEFAULT_ACCOUNTS),
    seed = seed,
    zipfExponent = options.double("zipf-exponent", KeyChooser.DEFAULT_ZIPF_EXPONENT),
    hotFraction = options.double("hot-fraction", KeyChooser.DEFAULT_HOT_FRACTION),
    hotKeys = options.int("hot-keys", 1),
)

/** What `--pids`, `--containers`, `--mongo`, `--postgres` and `--kafka` name to capture beside a run. */
private fun resources(options: Options): Resources = Resources(
    processes = options.list("pids").associate { it.substringBefore(':') to it.substringAfter(':').toLong() },
    containers = options.list("containers"),
    mongoContainer = options.optional("mongo"),
    postgresContainer = options.optional("postgres"),
    postgresDatabase = options.string("postgres-database", "transfer"),
    kafkaContainer = options.optional("kafka"),
)

/**
 * The [resources]' samplers through a run's phases: they start at the warm-up and are read at the measured phase's
 * edges, on threads of their own, so nothing they do delays a send or starts inside the measured phase (B.O-2).
 */
private class Sampling(private val resources: Resources) {
    private var preparing: Thread? = null
    private var starting: Thread? = null
    private var stopping: Thread? = null

    fun on(phase: String) {
        when (phase) {
            "warm-up" -> preparing = thread(name = "resources-prepare") { resources.prepare() }
            "measure" -> starting = thread(name = "resources-start") {
                preparing?.join()
                resources.start()
            }
            "drain" -> stopping = thread(name = "resources-stop") {
                starting?.join()
                resources.stopped.set(resources.stop())
            }
        }
    }

    /** After the drain: waits for the stop's reads, then the reads that start a process of their own. */
    fun finish() {
        stopping?.join()
        resources.complete()
    }
}

/** One open-loop run; its results go to `--out` as `result.json` and `summary.md`. */
private fun run(client: Client, targets: List<URI>, options: Options): Int {
    val mix = mixOf(options)
    val rate = options.double("rate", null)
    val warmUp = options.int("warm-up", 30)
    val measure = options.int("measure", 60)
    val seed = options.long("seed", DEFAULT_SEED)
    val keys = keyChooser(options, seed)
    val out = File(options.required("out")).apply { mkdirs() }
    val resources = resources(options)
    val startedAt = Instant.now()
    val sampling = Sampling(resources)
    val measured = OpenLoop(
        client = client,
        mix = mix,
        keys = keys,
        targets = targets,
        rate = rate,
        warmUpSeconds = warmUp,
        measureSeconds = measure,
        maxInFlight = options.int("max-in-flight", OpenLoop.DEFAULT_MAX_IN_FLIGHT),
    ) { phase ->
        println("[run] ${client.adapter.name} ${mix.label} @ $rate/s: $phase")
        sampling.on(phase)
    }.run()
    sampling.finish()
    val result = Report.result(
        label = options.string("label", "run"),
        adapter = client.adapter,
        mix = mix,
        rate = rate,
        warmUp = warmUp,
        measure = measure,
        keys = keys,
        seed = seed,
        targets = targets,
        startedAt = startedAt,
        measured = measured,
        resources = resources.stopped.get(),
        environment = Resources.environment(options.optional("repository")?.let(::File)),
        meta = options.meta(),
    )
    File(out, "result.json").writeText(Json.write(result) + "\n")
    val summary = Report.summary(result)
    File(out, "summary.md").writeText(summary)
    println(summary)
    return 0
}

/** The closed-loop saturation finder; its steps go to `--out` as `saturation.json`. */
private fun saturate(client: Client, targets: List<URI>, options: Options): Int {
    val mix = mixOf(options)
    val seed = options.long("seed", DEFAULT_SEED)
    val steps = ClosedLoop(
        client = client,
        mix = mix,
        keysOf = { worker -> keyChooser(options, seed + worker) },
        targets = targets,
        startConcurrency = options.int("start-concurrency", 1),
        maxConcurrency = options.int("max-concurrency", 256),
        warmUpSeconds = options.int("warm-up", 5),
        stepSeconds = options.int("step", 10),
    ) { println("[saturate] ${client.adapter.name} ${mix.label}: $it") }.run()
    val best = steps.maxByOrNull { it.throughput }
    val result = mapOf(
        "schema" to "wow-e2e-bench/saturation/1",
        "version" to client.adapter.name,
        "scenario" to mix.label,
        "keys" to keyChooser(options, seed).describe(),
        "targets" to targets.map { it.toString() },
        "saturationThroughput" to best?.throughput,
        "saturationConcurrency" to best?.concurrency,
        "note" to "Closed loop: the latencies only show the knee; open-loop runs at 50% and 80% of the saturation throughput give the results.",
        "steps" to steps.map { step ->
            mapOf(
                "concurrency" to step.concurrency,
                "throughput" to step.throughput,
                "ok" to step.ok,
                "errors" to step.errors,
                "p50Ms" to step.latency.percentile(50.0) / MICROS_PER_MILLI,
                "p99Ms" to step.latency.percentile(99.0) / MICROS_PER_MILLI,
            )
        },
        "meta" to options.meta(),
    )
    options.optional("out")?.let {
        File(it).apply { mkdirs() }.resolve("saturation.json").writeText(Json.write(result) + "\n")
    }
    println(
        "[saturate] saturation: ${best?.throughput?.let { "%.1f".format(it) }}/s at concurrency ${best?.concurrency}"
    )
    return 0
}

/** `--mix=E1:50,E4-state:40`, else the one `--scenario`. */
private fun mixOf(options: Options): Mix =
    options.optional("mix")?.let(Mix::parse) ?: Mix.of(Scenario.of(options.required("scenario")))

/** The `--name=value` options. */
class Options private constructor(private val values: Map<String, String>) {
    fun optional(name: String): String? = values[name]?.takeIf { it.isNotBlank() }
    fun required(name: String): String = optional(name) ?: throw IllegalArgumentException("--$name is required.")
    fun string(name: String, default: String): String = optional(name) ?: default
    fun int(name: String, default: Int): Int = optional(name)?.toInt() ?: default
    fun long(name: String, default: Long): Long = optional(name)?.toLong() ?: default
    fun double(name: String, default: Double?): Double =
        optional(name)?.toDouble() ?: default ?: throw IllegalArgumentException("--$name is required.")

    fun list(name: String): List<String> = optional(name)?.split(',')?.map {
        it.trim()
    }?.filter { it.isNotEmpty() }.orEmpty()

    /** `--meta=key=value;key=value`: what the harness records about the run's configuration. */
    fun meta(): Map<String, String> = optional("meta")?.split(';')?.filter { '=' in it }
        ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.orEmpty()

    companion object {
        fun parse(args: List<String>): Options = Options(
            args.associate { argument ->
                require(argument.startsWith("--")) { "Not an option: $argument" }
                val body = argument.removePrefix("--")
                if ('=' in body) body.substringBefore('=') to body.substringAfter('=') else body to "true"
            },
        )
    }
}

/** A percentage of [part] in [whole]. */
fun percent(part: Long, whole: Long): Double = if (whole == 0L) 0.0 else part * PERCENT / whole
