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

package me.ahoo.wow.routing.stage2

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.routing.Access
import me.ahoo.wow.spi.log.Partitioner
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** How a command ended, as the client sees it. */
enum class Outcome {
    COMMITTED,

    /** The domain refused it (an insufficient balance): an answer, not a failure. */
    REJECTED,

    /** `wow.command.contended`: two nodes appended to the aggregate at once. */
    CONTENDED,

    /** `wow.command.busy`: the executor's lane of the aggregate was full. */
    BUSY,

    /** Any other problem the node answered with. */
    FAILED,

    /** No answer: the connection failed or the request timed out. */
    ERROR,
    ;

    val failure: Boolean get() = this != COMMITTED && this != REJECTED
}

/**
 * Every command of one run: when it was due, when it was answered (-1 while it is not), how, which partition it
 * targets and which node received it.
 */
class Recording(capacity: Int) {
    val due = LongArray(capacity)
    val done = LongArray(capacity) { -1 }
    val outcome = arrayOfNulls<Outcome>(capacity)
    val code = arrayOfNulls<String>(capacity)
    val partition = IntArray(capacity)
    val receiver = IntArray(capacity)

    @Volatile
    var size: Int = 0
}

/**
 * Offers commands over the public HTTP routes of [nodes] at a fixed rate, open loop: a command is sent at its
 * scheduled time whether or not earlier ones have answered, and its latency is measured from that time, so a stalled
 * cluster cannot hide its queue (no coordinated omission). Each command goes to a random live receiver, the load
 * balancer of a deployment; a node taken out with [exclude] receives no further commands. The commands are stage 1's:
 * 90% deposits, 10% withdrawals, each awaited until committed (`Wow-Wait: committed`).
 */
class OpenLoad(
    private val nodes: List<NodeProcess>,
    private val access: Access,
    aggregates: Int,
    partitions: Int,
    private val runId: String,
) : AutoCloseable {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .executor(Executors.newFixedThreadPool(HTTP_THREADS) { Thread(it, "h2-http").apply { isDaemon = true } })
        .build()
    private val type = AggregateType(H2_CONTEXT, "account")
    private val partitionOf = IntArray(aggregates) {
        Partitioner.partitionOf(type.ref(Caller.DEFAULT_TENANT, Database.accountId(it)), partitions)
    }

    @Volatile
    private var receivers: List<Int> = nodes.indices.toList()
    private val inFlight = AtomicInteger()

    /** Takes the node [index] out of the receivers: it receives no command due from now on. */
    fun exclude(index: Int) {
        receivers = receivers - index
    }

    /**
     * Sends [rate] commands a second for [seconds] from [start] (a `System.nanoTime`), then waits for the answers for
     * at most [drain].
     */
    fun run(start: Long, rate: Double, seconds: Double, drain: Duration = DRAIN): Recording {
        val total = (rate * seconds).toInt()
        val interval = NANOS_PER_SECOND / rate
        val recording = Recording(total)
        val memo = "m".repeat(MEMO_LENGTH)
        var sequence = 0
        while (sequence < total) {
            val due = start + (sequence * interval).toLong()
            val wait = due - System.nanoTime()
            if (wait > SPIN_NANOS) {
                LockSupport.parkNanos(wait - SPIN_NANOS)
                continue
            }
            send(recording, sequence, due, memo)
            sequence++
            recording.size = sequence
        }
        val deadline = System.nanoTime() + drain.toNanos()
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(POLL_NANOS)
        }
        return recording
    }

    private fun send(recording: Recording, index: Int, due: Long, memo: String) {
        val random = ThreadLocalRandom.current()
        val account = access.next(random)
        val live = receivers
        val receiver = live[random.nextInt(live.size)]
        val amount = random.nextLong(1, MAX_AMOUNT)
        val withdraw = random.nextInt(PERCENT) < WITHDRAW_PERCENT
        recording.due[index] = due
        recording.partition[index] = partitionOf[account]
        recording.receiver[index] = receiver
        val request = HttpRequest.newBuilder(
            URI.create(
                "http://127.0.0.1:${nodes[receiver].publicPort}/$H2_CONTEXT/account/${Database.accountId(account)}/" +
                    if (withdraw) "withdraw" else "deposit",
            ),
        )
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Wow-Wait", "committed")
            .header("Wow-Wait-Timeout", "10s")
            .header("Wow-Request-Id", "$runId-$index")
            .POST(HttpRequest.BodyPublishers.ofString("""{"amount":$amount,"memo":"$memo"}"""))
            .build()
        inFlight.incrementAndGet()
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, failure ->
            try {
                val (outcome, code) = when {
                    failure != null -> Outcome.ERROR to failure.javaClass.simpleName
                    response.statusCode() == OK -> Outcome.COMMITTED to null
                    else -> classify(response.body())
                }
                recording.code[index] = code
                recording.outcome[index] = outcome
                recording.done[index] = System.nanoTime()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    override fun close() {
        http.shutdownNow()
        http.awaitTermination(Duration.ofSeconds(CLOSE_SECONDS))
        (http.executor().orElse(null) as? java.util.concurrent.ExecutorService)?.let {
            it.shutdownNow()
            it.awaitTermination(CLOSE_SECONDS, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val OK = 200
        private const val HTTP_THREADS = 8
        private const val MEMO_LENGTH = 8
        private const val MAX_AMOUNT = 100L
        private const val PERCENT = 100
        private const val WITHDRAW_PERCENT = 10
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val SPIN_NANOS = 60_000L
        private const val POLL_NANOS = 5_000_000L
        private const val CLOSE_SECONDS = 5L
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val DRAIN: Duration = Duration.ofSeconds(20)
        private val CODE = Regex(""""code"\s*:\s*"([^"]+)"""")

        /** The outcome of a problem+json answer, by its code. */
        fun classify(body: String?): Pair<Outcome, String> {
            val code = body?.let { CODE.find(it)?.groupValues?.get(1) } ?: "unknown"
            val outcome = when {
                code.startsWith("account.") -> Outcome.REJECTED
                code == "wow.command.contended" -> Outcome.CONTENDED
                code == "wow.command.busy" -> Outcome.BUSY
                else -> Outcome.FAILED
            }
            return outcome to code
        }
    }
}
