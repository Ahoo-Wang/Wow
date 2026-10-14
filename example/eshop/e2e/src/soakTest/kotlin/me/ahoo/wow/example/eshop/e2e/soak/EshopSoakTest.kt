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

package me.ahoo.wow.example.eshop.e2e.soak

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.e2e.json
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The eShop's fault-injection soak (work package C.1, decision D196): `./gradlew -Pwow.scope=v10
 * :eshop-e2e:soakTest`, one hour by default, `-Pwow.soak.mode=short` for five minutes ([SoakConfig] has every
 * property).
 *
 * It starts [SoakStack] (the basket, ordering, inventory and payment services on PostgreSQL and Kafka), drives the
 * checkout flow with [SoakLoad] while [FaultInjector] kills and replaces nodes and pauses or restarts PostgreSQL and
 * Kafka, samples the checkpoints and the parked ledger as it goes, lets the flows settle once the load and the faults
 * stop, then checks the [Invariants] over the whole run. It writes `build/eshop-soak/report-<time>.json` (the counts,
 * the fault timeline, every invariant with its evidence) and `requests-<time>.jsonl` (every request's attempts), and
 * fails when an invariant is violated.
 */
class EshopSoakTest {
    @Test
    fun `the eShop should keep its invariants through node kills and backend restarts`() {
        val config = SoakConfig.fromSystemProperties()
        val stamp = STAMP.format(Instant.now())
        config.dir.mkdirs()
        // The services' output, kept for the evidence of a violation.
        System.setProperty("eshop.logs", File(config.dir, "logs-$stamp").path)
        log("config ${config.describe()}")
        val begun = System.currentTimeMillis()
        val report = linkedMapOf<String, Any?>("config" to config.describe(), "began" to begun)
        var violations: Long
        SoakStack(config, ::log).use { stack ->
            stack.start()
            seed(stack)
            log("started in ${System.currentTimeMillis() - begun} ms")
            val faults = FaultInjector(stack, config, ::log)
            val checks = SoakChecks(stack, config, faults, ::log)
            val load = SoakLoad(stack, config, ::log)
            checks.startSampling()
            val loadBegan = System.currentTimeMillis()
            load.start()
            faults.start()
            val until = System.nanoTime() + config.duration.toNanos()
            while (System.nanoTime() < until) {
                Thread.sleep(minOf(PROGRESS.toMillis(), (until - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(1))
                log("progress: ${SoakSummary.progress(load.orders.toList())}")
            }
            faults.stop()
            load.stop()
            val loadEnded = System.currentTimeMillis()
            log("load stopped after ${load.orders.size} orders; awaiting the stack healthy")
            check(stack.awaitHealthy()) { "The stack is not healthy after the load." }
            checks.settle(load)
            checks.stopSampling()
            checks.finalChecks(load)
            val orders = load.orders.toList()
            report["load"] = mapOf("began" to loadBegan, "ended" to loadEnded, "millis" to loadEnded - loadBegan)
            report["settlement"] = checks.settlement
            report["counts"] = SoakSummary.counts(orders)
            report["faults"] = faults.timeline.map(SoakSummary::fault)
            report["samples"] = mapOf("taken" to checks.samples, "failed" to checks.failedSamples)
            report["parkedSeen"] = checks.parkedSeen.values.toList()
            report["invariants"] = checks.invariants.all.map { it.describe() }
            violations = checks.invariants.all.sumOf { it.violated }
            report["violations"] = violations
            report["ended"] = System.currentTimeMillis()
            File(config.dir, "requests-$stamp.jsonl").printWriter().use { out ->
                orders.forEach { order -> order.requests.toList().forEach { out.println(json.writeValueAsString(SoakSummary.request(order, it))) } }
            }
        }
        val file = File(config.dir, "report-$stamp.json")
        file.writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        log("report: ${file.absolutePath}")
        @Suppress("UNCHECKED_CAST")
        (report["invariants"] as List<Map<String, Any?>>).forEach { log("invariant ${it["name"]}: ok=${it["ok"]} checked=${it["checked"]} violations=${it["violations"]}") }
        violations.assert().describedAs("violations, see ${file.absolutePath}").isZero()
    }

    /** The stock every order draws on, received once each with a request id of its own. */
    private fun seed(stack: SoakStack) {
        val inventory = stack.nodes("inventory").first().service
        mapOf(
            SoakLoad.PRODUCT_A to SoakChecks.SEED_STOCK,
            SoakLoad.PRODUCT_B to SoakChecks.SEED_STOCK,
            SoakLoad.PRODUCT_SHORT to SoakChecks.SHORT_STOCK,
        ).forEach { (product, quantity) ->
            val received = inventory.post(
                "/eshop-inventory/stock/$product/receive-stock",
                """{"quantity":$quantity}""",
                mapOf(SoakLoad.REQUEST_ID to "seed:$product", SoakLoad.WAIT to "snapshot"),
            )
            check(received.status == OK) { "Receiving $product answered ${received.status}: ${received.body}" }
        }
    }

    private companion object {
        const val OK = 200
        const val NANOS_PER_MILLI = 1_000_000L
        val PROGRESS: Duration = Duration.ofMinutes(1)
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        fun log(message: String) {
            println("[eshop-soak] ${Instant.now()} $message")
        }
    }
}
