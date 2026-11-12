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

package me.ahoo.wow.example.eshop.e2e.cluster

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import kotlin.concurrent.thread

/**
 * The rc drill's end-to-end run (test/rc-drill/lib/verdict.sh): the eShop deployed from a release's published artifacts
 * in the drill's production shape, driven from outside the cluster through its ingress, once per step of the drill.
 * Each run does what `ga.scenarios` names, in that order, then judges the whole drill so far: the run's record is kept
 * across runs ([RunLedger], `ga.state`), so a verdict after an operation the drill made from outside (an upgrade, a
 * zone lost, a store restarted, a backup restored) holds the stores to everything the earlier runs did.
 *
 * - `functional`: the catalog and the stock filed, the full chain, a declined payment, a stock too short, the
 *   boundary (the public ports behind the gateway, the internal ports the platform's alone), the products' search.
 * - `owner-deletion`: the partition owner of an order force-deleted mid-checkout.
 * - `ops`: a payment parked by the payment gateway's outage, retried from ops by an operator.
 * - `load`: checkouts by `ga.load.clients` buyers at once, a probe measuring every failure, until the file
 *   `ga.load.stop-file` appears; `load.started` in the report's folder once the first checkout is answered, so the drill
 *   starts its operation under load. The window of failures must be within `ga.load.bound`.
 * - `verdict`: the verdict alone.
 *
 * Every run then checks that no Wow pod's container restarted (a liveness failure, a crash, memory) and that every Wow
 * pod is ready, and judges the invariants ([ClusterVerdict]). The GA end-to-end run's checks of the logs stay its own:
 * the drill's faults are made between runs, outside any one run's windows.
 */
@Suppress("FunctionNaming") // detekt names test functions so only under src/test; this is src/clusterTest.
class RcDrillTest {
    private val reportDir = File(System.getProperty("ga.report", "build/rc-drill"))
    private val report = ClusterReport(reportDir, "mongo")
    private val kube = Kube { println("[rc-drill] $it") }
    private val ledger = checkNotNull(RunLedger.fromSystem()) { "ga.state is required: the drill's record" }
    private val flows = ShopFlows(
        Shop(
            System.getProperty("ga.gateway", "http://127.0.0.1:30080"),
            Tokens(checkNotNull(System.getProperty("ga.secret")) { "ga.secret is required: the run's JWT secret" }),
        ),
        ledger,
    )
    private val reads = StoreReads.of("mongo", kube, "")
    private val verdict = ClusterVerdict(flows, reads) { println("[rc-drill] $it") }
    private val network = PodNetwork(kube, report)
    private val ops = OpsDesk(kube, report, flows.shop)
    private val partitions = System.getProperty("ga.partitions", "64").toInt()
    private val probes = ClusterProbes(kube, report, flows, reads, network, partitions)
    private val search = SearchStore(kube, network, flows, report)
    private val scenarios = Scenarios(flows, kube, report, network, probes, ops, reads)
    private val selected = System.getProperty("ga.scenarios", "").split(',', ' ').filter { it.isNotBlank() }

    @Test
    fun `the drill's scenarios hold, and the whole drill's invariants with them`() {
        check(selected.isNotEmpty()) { "ga.scenarios names none" }
        flows.onLate = { report.note(it) }
        verdict.startSampling()
        try {
            selected.forEach { name ->
                when (name) {
                    "functional" -> functional()
                    "owner-deletion" -> scenario("cluster", "partition owner deleted mid-checkout") {
                        scenarios.deleteOwnerMidCheckout("carol-${System.currentTimeMillis()}")
                    }
                    "ops" -> scenario("ops", "parked, retried from ops") {
                        scenarios.parkedRetriedFromOps("olga-${System.currentTimeMillis()}")
                    }
                    "load" -> scenario("load", "checkouts under the drill's operation", LOAD_BUDGET) { load() }
                    "verdict" -> Unit
                    else -> error("no scenario $name: functional, owner-deletion, ops, load or verdict")
                }
            }
            scenario("operations", "no Wow container restarted, every Wow pod ready") { wowPods() }
        } finally {
            verdict.stopSampling()
        }
        judge()
    }

    private fun functional() {
        // The catalog is filed once per drill: a later functional run (after a reinstall) finds it.
        if (flows.received.isEmpty()) scenario("functional", "catalog and stock") { scenarios.catalogAndStock() }
        val buyer = "drill-${System.currentTimeMillis()}"
        scenario("functional", "full chain") { scenarios.fullChain("$buyer-a").second }
        scenario("functional", "declined payment") { scenarios.declinedPayment("$buyer-e") }
        scenario("functional", "stock rejection") { scenarios.stockRejection("$buyer-b") }
        scenario("security", "public ports behind the gateway") { scenarios.publicPorts(emptySet()) }
        scenario("security", "internal ports") { scenarios.internalPorts(emptySet()) }
        scenario("security", "internal mesh") { network.mesh("drill") }
        scenario("functional", "product search on Elasticsearch") { search.served() }
    }

    /**
     * Checkouts by `ga.load.clients` buyers until the drill's stop file appears, the probe's failure window held to
     * `ga.load.bound`. `load.started` marks the first answered checkout.
     */
    private fun load(): String {
        val stop = File(checkNotNull(System.getProperty("ga.load.stop-file")) { "ga.load.stop-file is required" })
        val bound = Duration.parse(System.getProperty("ga.load.bound", "PT60S"))
        val clients = System.getProperty("ga.load.clients", "4").toInt()
        val label = reportDir.name
        val answered = flows.numbers.size
        val outages = Outages("checkout to the ordering", probes::probeToOrdering).start()
        val loads = (1..clients).map { Load(flows, "load-$label-$it").start() }
        try {
            flows.eventually("the load's first answered checkout", Duration.ofMinutes(2)) {
                flows.numbers.size > answered
            }
            File(reportDir, "load.started").apply { parentFile.mkdirs() }.writeText("${System.currentTimeMillis()}\n")
            val until = System.nanoTime() + LOAD_BUDGET.toNanos()
            while (!stop.exists() && System.nanoTime() < until) Thread.sleep(POLL.toMillis())
            check(stop.exists()) { "the drill did not stop the load within $LOAD_BUDGET" }
        } finally {
            loads.forEach(Load::stop)
        }
        val window = outages.stop(label, bound).also(report::window)
        check(window.ok) {
            "failures for ${window.millis} ms, over the bound $bound (${window.failures} of ${window.probes})"
        }
        return "${loads.sumOf { it.sent }} checkouts by $clients buyers; failures for ${window.millis} ms " +
            "(${window.failures} of ${window.probes} probes), within $bound"
    }

    /** No Wow container restarted (liveness, a crash, memory) and every Wow pod ready, at the end of this run. */
    private fun wowPods(): String {
        val wow = kube.allPods().filter { pod -> WOW.any { pod.name.startsWith(it) } }
        val restarted = wow.filter { it.restarts > 0 }
        check(restarted.isEmpty()) {
            "restarted: " + restarted.map { "${it.name} ×${it.restarts} (${it.lastTerminated ?: "no reason recorded"})" }
        }
        val unready = wow.filter { !it.ready && !it.deleting }
        check(unready.isEmpty()) { "not ready: ${unready.map { it.name }}" }
        return "${wow.size} Wow pods, none restarted, all ready"
    }

    private fun judge() {
        var failure: Throwable? = null
        val judging = thread(name = "verdict", isDaemon = true) {
            runCatching {
                verdict.settle()
                verdict.judge()
            }.onFailure { failure = it }
        }
        judging.join(VERDICT_BUDGET.toMillis())
        if (judging.isAlive) report.check("verdict", "judged in time", false, "not judged within $VERDICT_BUDGET")
        failure?.let { report.check("verdict", "judged", false, "${it.javaClass.simpleName}: ${it.message}") }
        val passed = report.write(verdict.all)
        verdict.all.filter { it.violated > 0 }.forEach {
            println("[rc-drill] violated ${it.name}: ${it.describe()["evidence"]}")
        }
        report.failed.map { "${it.scenario} / ${it.name}: ${it.detail}" }.assert().isEmpty()
        verdict.all.sumOf { it.violated }.assert().isEqualTo(0L)
        passed.assert().isTrue()
    }

    /** Runs a scenario's [body] within its [budget], recording it in the report, a failure before JUnit sees it. */
    @Suppress("TooGenericExceptionCaught") // Any failure is recorded, then thrown on.
    private fun scenario(scenario: String, name: String, budget: Duration = SCENARIO_BUDGET, body: () -> String) {
        var detail: String? = null
        var failure: Throwable? = null
        val worker = thread(name = "scenario-$name", isDaemon = true) {
            try {
                detail = body()
            } catch (failed: Throwable) {
                failure = failed
            }
        }
        worker.join(budget.toMillis())
        val error = when {
            worker.isAlive -> AssertionError("$scenario / $name exceeded its budget of $budget").also {
                worker.interrupt()
            }
            else -> failure
        }
        report.check(scenario, name, error == null, detail ?: "${error?.javaClass?.simpleName}: ${error?.message}")
        if (error != null) throw error
    }

    private companion object {
        /** The Wow pods by their names' starts: the services' Deployments are named after the services. */
        val WOW = listOf("eshop-", "ops-service-", "view-store-service-", "analytics-catalog-service-")
        val SCENARIO_BUDGET: Duration = Duration.ofMinutes(15)
        val LOAD_BUDGET: Duration = Duration.ofMinutes(90)
        val VERDICT_BUDGET: Duration = Duration.ofMinutes(15)
        val POLL: Duration = Duration.ofSeconds(1)
    }
}
