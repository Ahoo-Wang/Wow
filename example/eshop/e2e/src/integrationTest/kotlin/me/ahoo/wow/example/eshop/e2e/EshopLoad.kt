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

package me.ahoo.wow.example.eshop.e2e

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * The eShop's load script (work package E.6): `./gradlew -Pwow.scope=v10 :eshop-e2e:eshopLoad`. It starts
 * [EshopStack], then drives checkouts through the full chain from [concurrency] buyers at a time: each buyer adds a
 * product to the basket, checks it out with the `Wow-Wait` [EshopStack.CHECKOUT_TO_RESERVED], which answers once the
 * order's reservation is committed in the inventory, and reads the order until it is paid: the reservation's success
 * receipt confirms the stock, which no wait follows (decision D192). A checkout's latency is from the checkout to the
 * paid order. It reports the throughput and the latency percentiles of the measured checkouts, after [warmUp]
 * unmeasured ones.
 *
 * The Gradle properties `eshop.load.checkouts` (200), `eshop.load.concurrency` (8) and `eshop.load.warm-up` (40) set
 * the run. It is not part of any check.
 */
fun main() {
    val checkouts = Integer.getInteger("eshop.load.checkouts", DEFAULT_CHECKOUTS)
    val concurrency = Integer.getInteger("eshop.load.concurrency", DEFAULT_CONCURRENCY)
    val warmUp = Integer.getInteger("eshop.load.warm-up", DEFAULT_WARM_UP)
    report("Starting the eShop: 5 services, PostgreSQL, Kafka and ClickHouse.")
    val begun = System.nanoTime()
    EshopStack().use { stack ->
        report("Started in ${Duration.ofNanos(System.nanoTime() - begun).toSeconds()} s.")
        stack.catalog.post("/eshop-catalog/product/$PRODUCT/create-product", """{"name":"Load mug","price":$PRICE}""")
        stack.inventory.post("/eshop-inventory/stock/$PRODUCT/receive-stock", """{"quantity":${checkouts + warmUp}}""")
        val load = EshopLoad(stack, concurrency)
        load.run("warm-up", 0 until warmUp)
        val result = load.run("measured", warmUp until warmUp + checkouts)
        report(result.toString())
        check(result.failures == 0) { "${result.failures} checkouts did not reach the paid order." }
    }
}

/** Drives checkouts on [stack], [concurrency] at a time. */
class EshopLoad(private val stack: EshopStack, private val concurrency: Int) {
    /** Checks out one basket for each buyer `load-<n>` of [buyers]. */
    fun run(phase: String, buyers: IntRange): Result {
        val pool = Executors.newFixedThreadPool(concurrency)
        val begun = System.nanoTime()
        val latencies = try {
            pool.invokeAll(buyers.map { buyer -> Callable { checkout("load-$buyer") } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val elapsed = Duration.ofNanos(System.nanoTime() - begun)
        return Result(phase, concurrency, latencies.filterNotNull().sorted(), latencies.count { it == null }, elapsed)
    }

    /** The checkout's latency, or null when it did not end with the paid order. */
    private fun checkout(buyer: String): Duration? {
        stack.basket.post(
            "/eshop-basket/basket/$buyer/add-basket-item",
            """{"productId":"$PRODUCT","unitPrice":$PRICE,"quantity":1}""",
        ).also { check(it.status == OK) { "Adding to the basket of $buyer answered ${it.status}: ${it.body}" } }
        val begun = System.nanoTime()
        val answer = stack.basket.post(
            "/eshop-basket/basket/$buyer/checkout-basket",
            "{}",
            mapOf("Wow-Wait" to EshopStack.CHECKOUT_TO_RESERVED, "Wow-Wait-Timeout" to "60s"),
        )
        val reserved = answer.status == OK && json.readTree(answer.body).let { result ->
            result["status"].asString() == "SUCCEEDED" && result["function"]["name"].asString() == "ReserveStock"
        }
        if (!reserved) {
            report("The checkout of $buyer answered ${answer.status}: ${answer.body.take(MESSAGE)}")
            return null
        }
        val deadline = begun + PAID_TIMEOUT.toNanos()
        while (stack.orderStatus("$buyer-1") != "PAID") {
            if (System.nanoTime() > deadline) {
                report("The order of $buyer was not paid within $PAID_TIMEOUT.")
                return null
            }
            Thread.sleep(POLL.toMillis())
        }
        return Duration.ofNanos(System.nanoTime() - begun)
    }

    /** One phase of a run: its sorted [latencies], its [failures] and how long it took. */
    data class Result(
        val phase: String,
        val concurrency: Int,
        val latencies: List<Duration>,
        val failures: Int,
        val elapsed: Duration,
    ) {
        val throughput: Double get() = latencies.size / (elapsed.toMillis() / MILLIS_PER_SECOND)

        fun percentile(p: Double): Duration =
            latencies[(ceil(p / PERCENT * latencies.size).toInt() - 1).coerceIn(0, latencies.lastIndex)]

        override fun toString(): String = if (latencies.isEmpty()) {
            "$phase: no checkout paid, $failures failed"
        } else {
            "$phase: ${latencies.size} checkouts paid, $failures failed, concurrency $concurrency, " +
                "${elapsed.toMillis()} ms, ${"%.1f".format(throughput)} checkouts/s; latency " +
                "p50 ${percentile(P50).toMillis()} ms, p95 ${percentile(P95).toMillis()} ms, " +
                "p99 ${percentile(P99).toMillis()} ms, max ${latencies.last().toMillis()} ms"
        }
    }
}

private fun report(message: String) = println("[eshop-load] $message")

private const val OK = 200
private const val PRODUCT = "load-mug"
private const val PRICE = "8.50"
private const val DEFAULT_CHECKOUTS = 200
private const val DEFAULT_CONCURRENCY = 8
private const val DEFAULT_WARM_UP = 40
private const val MESSAGE = 500
private val PAID_TIMEOUT: Duration = Duration.ofSeconds(60)
private val POLL: Duration = Duration.ofMillis(20)
private const val PERCENT = 100.0
private const val MILLIS_PER_SECOND = 1000.0
private const val P50 = 50.0
private const val P95 = 95.0
private const val P99 = 99.0
