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

package me.ahoo.wow.benchmark.transfer

import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * The L1 component benchmark of D224's ladder: the benchmark's domain on the V10 kernel and one store, in this JVM,
 * without HTTP or a load generator. It opens `--accounts` accounts, then sends `--ops` commands of `--scenario` (E1 a
 * deposit answered once committed, E3 a transfer answered once the saga's entry is committed, E1-large an annotation
 * of five ~2 KB notes) through the node's [CommandClient], `--concurrency` at a time, after `--warm-up-ops` unrecorded
 * ones. It counts what each operation costs (the store's round trips by command, the bytes allocated) and times it
 * (throughput and latency of a closed loop, short and indicative: an end-to-end run gives the conclusions), and
 * writes `component.json` to `--out`. Counts do not depend on the machine's load, so they can run anywhere (the
 * tiered measurement of D224); a core-path change attaches at least these counts.
 *
 * Run it on the install's class path (every jar of its `lib`) with main class `ComponentBenchKt`; bench.sh's
 * `component` command does.
 */
fun main(args: Array<String>) {
    val options = args.associate { argument ->
        require(argument.startsWith("--") && '=' in argument) { "Not an option: $argument" }
        argument.removePrefix("--").substringBefore('=') to argument.substringAfter('=')
    }
    val backend = options["backend"] ?: "mongo"
    val bench = ComponentRun(
        scenario = options["scenario"] ?: "E1",
        accounts = options["accounts"]?.toInt() ?: DEFAULT_ACCOUNTS,
        ops = options["ops"]?.toInt() ?: DEFAULT_OPS,
        warmUpOps = options["warm-up-ops"]?.toInt() ?: DEFAULT_WARM_UP_OPS,
        concurrency = options["concurrency"]?.toInt() ?: DEFAULT_CONCURRENCY,
    )
    val out = File(options["out"] ?: ".").apply { mkdirs() }
    // No web server: the node's command client is called directly. Its internal port takes a free one.
    val context = SpringApplicationBuilder(TransferServer::class.java, ComponentCounters::class.java)
        .web(WebApplicationType.NONE)
        .profiles(backend)
        .run(
            // Command-line properties, over application.yaml's: a free internal port, and any spring.* or wow.* given.
            "--wow.internal.port=0",
            *options.filterKeys { it.startsWith("spring.") || it.startsWith("wow.") }
                .map { "--${it.key}=${it.value}" }
                .toTypedArray(),
        )
    val result = try {
        bench.run(context.getBean(CommandClient::class.java), context.getBean(StoreCounter::class.java))
    } finally {
        context.close()
    }
    val json = toJson(result + ("backend" to backend))
    File(out, "component.json").writeText(json + "\n")
    println(json)
    exitProcess(0)
}

private const val DEFAULT_ACCOUNTS = 1_000
private const val DEFAULT_OPS = 20_000
private const val DEFAULT_WARM_UP_OPS = 2_000
private const val DEFAULT_CONCURRENCY = 64
private const val BALANCE = 1_000_000_000_000L
private const val NOTES = 5
private const val NOTE_BYTES = 2_048
private const val NANOS_PER_MILLI = 1_000_000.0
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val PERCENT = 100.0
private const val P50 = 50.0
private const val P99 = 99.0

/** One component run: the accounts opened, then the warm-up and the measured operations. */
class ComponentRun(
    private val scenario: String,
    private val accounts: Int,
    private val ops: Int,
    private val warmUpOps: Int,
    private val concurrency: Int,
) {
    private val e3Plan = WaitPlan.of(Hop.committed(), Hop.handled("TransferSaga.onPrepared"), Hop.committed())

    // The large-payload variant's notes: fixed pseudo-random text, so no store compresses it away.
    private val notes = List(NOTES) { index ->
        val random = Random(index.toLong())
        String(CharArray(NOTE_BYTES) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }

    fun run(client: CommandClient, counter: StoreCounter): Map<String, Any?> = runBlocking(Dispatchers.Default) {
        val seedStarted = System.nanoTime()
        loop(accounts) { index ->
            client.wait(
                OpenAccount("owner-${index % OWNERS}", BALANCE),
                ACCOUNT_TYPE.ref(TENANT, accountId(index)),
                SendOptions.of(wait = WaitPlan.SNAPSHOT),
            )
        }
        val seedSeconds = (System.nanoTime() - seedStarted) / NANOS_PER_SECOND
        val next = AtomicInteger()
        loop(warmUpOps) { operation(client, next.getAndIncrement()) }
        val memory = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java)
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        val collectionsBefore = collectors.sumOf { it.collectionCount }
        val collectionMillisBefore = collectors.sumOf { it.collectionTime }
        counter.reset()
        val allocatedBefore = memory.totalThreadAllocatedBytes
        val latencies = LongArray(ops)
        val failures = ConcurrentHashMap<String, LongAdder>()
        val started = System.nanoTime()
        loop(ops) { index ->
            val sent = System.nanoTime()
            val outcome = runCatching { operation(client, next.getAndIncrement()) }
            latencies[index] = System.nanoTime() - sent
            val failure = outcome.exceptionOrNull()?.javaClass?.simpleName
                ?: outcome.getOrNull()?.takeIf { it.status != CommandResult.Status.SUCCEEDED }?.status?.name
            failure?.let { failures.computeIfAbsent(it) { LongAdder() }.increment() }
        }
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
        val allocated = memory.totalThreadAllocatedBytes - allocatedBefore
        val trips = counter.snapshot()
        latencies.sort()
        fun percentile(p: Double): Double = latencies[((latencies.size - 1) * p / PERCENT).toInt()] / NANOS_PER_MILLI
        mapOf(
            "schema" to "wow-e2e-bench/component/1",
            "scenario" to scenario,
            "accounts" to accounts,
            "ops" to ops,
            "warmUpOps" to warmUpOps,
            "concurrency" to concurrency,
            "seedSeconds" to seedSeconds,
            "seconds" to seconds,
            "throughput" to ops / seconds,
            "failed" to failures.mapValues { it.value.sum() },
            "latencyMs" to mapOf(
                "p50" to percentile(P50),
                "p99" to percentile(P99),
                "max" to latencies.last() / NANOS_PER_MILLI,
            ),
            "storeRoundTripsPerOp" to trips.values.sum().toDouble() / ops,
            "storeRoundTripsByCommand" to trips.mapValues { it.value.toDouble() / ops },
            "allocatedBytesPerOp" to allocated.toDouble() / ops,
            "gcCollections" to collectors.sumOf { it.collectionCount } - collectionsBefore,
            "gcMillis" to collectors.sumOf { it.collectionTime } - collectionMillisBefore,
            "note" to "Closed loop in one JVM: counts are the result, timings only indicate; the store's round trips " +
                "include the node's background work (tails, snapshots, leases) over the measured phase.",
        )
    }

    /** Operation [index] of the scenario: a deposit, a transfer or an annotation on the account it falls on. */
    private suspend fun operation(client: CommandClient, index: Int): CommandResult {
        val account = index % accounts
        val ref = ACCOUNT_TYPE.ref(TENANT, accountId(account))
        return when (scenario) {
            "E1" -> client.wait(Deposit(1), ref, SendOptions.of(wait = WaitPlan.COMMITTED))
            "E3" -> client.wait(Prepare(accountId((account + 1) % accounts), 1), ref, SendOptions.of(wait = e3Plan))
            "E1-large" -> client.wait(Annotate(notes), ref, SendOptions.of(wait = WaitPlan.COMMITTED))
            else -> throw IllegalArgumentException("Unknown scenario '$scenario': E1, E3 or E1-large.")
        }
    }

    /** Runs [block] for 0 until [count], [concurrency] at a time. */
    private suspend fun loop(count: Int, block: suspend (Int) -> Unit) = coroutineScope {
        val permits = Semaphore(concurrency)
        repeat(count) { index -> launch { permits.withPermit { block(index) } } }
    }

    private companion object {
        const val TENANT = "(0)"
        const val OWNERS = 100
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** The load generator's account ids, so a component run and an end-to-end run share their keys. */
        fun accountId(index: Int): String = "acc-%07d".format(index)
    }
}

/** [value] as JSON: maps, lists, strings, numbers, booleans and nulls. */
fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${toJson(it.key.toString())}:${toJson(it.value)}" }
    is Iterable<*> -> value.joinToString(",", "[", "]") { toJson(it) }
    else -> toJson(value.toString())
}

/** Counts the store's round trips by command name, from the MongoDB driver's command events or the JDBC calls. */
class StoreCounter {
    private val counts = ConcurrentHashMap<String, LongAdder>()

    fun count(name: String) = counts.computeIfAbsent(name) { LongAdder() }.increment()

    fun reset() = counts.clear()

    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.sum() }.toSortedMap()
}

/**
 * The component run's counters: a MongoDB [CommandListener] the `mongo` profile's client takes (MongoBackend), and a
 * wrapper of the `postgres` profile's DataSource that counts every statement executed and every commit and rollback.
 * ComponentBench passes it as a source of its own; it is no `@Configuration`, so the end-to-end server's component scan
 * of this package leaves it out and its store calls go unwrapped (B.O-1).
 */
class ComponentCounters {
    @Bean
    fun storeCounter(): StoreCounter = StoreCounter()

    @Bean
    fun componentCommandListener(counter: StoreCounter): CommandListener = object : CommandListener {
        override fun commandStarted(event: CommandStartedEvent) = counter.count(event.commandName)
    }

    @Bean
    fun componentDataSourceCounter(counter: StoreCounter): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is DataSource && !Proxy.isProxyClass(bean.javaClass)) counted(bean, counter) else bean
    }
}

private val EXECUTIONS = setOf("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeUpdate")

/**
 * [target] seen as [type]: each call reported to [onCall] by its method's name and arguments, its result passed through
 * [wrap] with the same arguments.
 */
private fun <T : Any> proxy(
    target: T,
    type: Class<T>,
    onCall: (String, Array<Any?>) -> Unit,
    wrap: (Any?, Array<Any?>) -> Any? = { result, _ -> result },
): T =
    type.cast(
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            InvocationHandler { _, method, arguments ->
                val given: Array<Any?> = arguments ?: emptyArray()
                onCall(method.name, given)
                try {
                    wrap(method.invoke(target, *given), given)
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            },
        ),
    )

/**
 * [dataSource] with its statements' executions, by [what each runs][statementLabel], and its connections' commits and
 * rollbacks counted on [counter].
 */
private fun counted(dataSource: DataSource, counter: StoreCounter): DataSource {
    fun statement(value: Any?, sql: String?): Any? {
        val onStatement: (String, Array<Any?>) -> Unit = { name, arguments ->
            if (name in EXECUTIONS) counter.count(statementLabel(sql ?: arguments.firstOrNull() as? String))
        }
        return when (value) {
            is CallableStatement -> proxy(value, CallableStatement::class.java, onStatement)
            is PreparedStatement -> proxy(value, PreparedStatement::class.java, onStatement)
            is Statement -> proxy(value, Statement::class.java, onStatement)
            else -> value
        }
    }
    val onConnection: (String, Array<Any?>) -> Unit = { name, _ ->
        if (name == "commit" || name == "rollback") counter.count(name)
    }
    fun connection(value: Any?): Any? = if (value is Connection) {
        proxy(value, Connection::class.java, onConnection) { result, arguments ->
            statement(result, arguments.firstOrNull() as? String)
        }
    } else {
        value
    }
    return proxy(dataSource, DataSource::class.java, { _, _ -> }) { result, _ -> connection(result) }
}

private val SQL_TABLE = Regex("""\b(?:from|into|update|join)\s+("?[\w.]+"?)""", RegexOption.IGNORE_CASE)

/** A statement's count key: `statement` with its verb and first table, `select wow_events` say, so a run shows each. */
internal fun statementLabel(sql: String?): String {
    val text = sql?.trim()?.takeIf { it.isNotEmpty() } ?: return "statement"
    val verb = text.substringBefore(' ').lowercase()
    val table = SQL_TABLE.find(text)?.groupValues?.get(1)?.trim('"')
    return listOfNotNull("statement", verb, table).joinToString(" ")
}
