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
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.example.eshop.e2e.json
import me.ahoo.wow.example.eshop.e2e.soak.SoakDatabase
import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * The GA end-to-end scenarios (roadmap 9.3g, decision D317, G.3) against the eShop that ga-e2e.yml deployed on a kind
 * cluster with Calico, behind CoSec Gateway: every client request goes through the gateway's node port, every fault
 * through kubectl. Each scenario records its checks in the report; the last test judges the run by the soak's
 * invariants and fails on any violated invariant, failed check or exceeded bound.
 *
 * 1. Functional, through the gateway: the full checkout chain with its 15-hop wait, a declined payment, a stock
 *    rejection and its compensation, the payment grace period (a timer), and `remote_ip` recorded as the client's.
 * 2. Security, negative: the public and internal ports unreachable around the gateway, `Wow-Service` granting nothing
 *    on the public port and `Wow-Call` refused there.
 * 3. The ops loop: a parked payment reaches ops, an operator resumes it there, and the entry is gone.
 * 4. Cluster behaviour, each fault's unavailability window measured and bounded: the ordering scaled 2→3→2, a rolling
 *    restart, the pod owning an order's partition deleted mid-checkout, a Kafka broker restart and, on the mongo arm,
 *    the primary stepping down.
 * 5. Observability: probes green outside the faults, no restarts, the key metrics, no unexpected ERROR in the logs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("FunctionNaming") // detekt names test functions so only under src/test; this is src/clusterTest.
class GaE2eTest {
    private val backend = System.getProperty("ga.backend", "postgres")
    private val report = ClusterReport(File(System.getProperty("ga.report", "build/ga-e2e")), backend)
    private val kube = Kube { println("[ga-e2e] $it") }
    private val flows = ShopFlows(
        Shop(
            System.getProperty("ga.gateway", "http://localhost:30080"),
            Tokens(System.getProperty("ga.secret", SECRET))
        ),
    )
    private val shop = flows.shop
    private val postgres = if (backend == "postgres") {
        System.getProperty(
            "ga.postgres",
            "jdbc:postgresql://localhost:30432"
        )
    } else {
        null
    }
    private val verdict = ClusterVerdict(flows, postgres) { println("[ga-e2e] $it") }
    private val health = HealthWatch(kube, report)
    private val network = PodNetwork(kube, report)
    private val ops = OpsDesk(kube, report, shop)
    private val evidence = Evidence(kube, File(System.getProperty("ga.report", "build/ga-e2e"), "evidence"))
    private val partitions = System.getProperty("ga.partitions", "8").toInt()

    /** The services this run left out of the deployment (`ga.excluded`), for the reason its report states. */
    private val excluded = System.getProperty("ga.excluded", "").split(' ', ',').filter { it.isNotBlank() }.toSet()
    private val probes = ClusterProbes(kube, report, flows, verdict, network, partitions, postgres != null)

    init {
        flows.onLate = { report.note(it) }
        if (excluded.isNotEmpty()) {
            report.note(
                "excluded from this run: ${excluded.joinToString()} (${System.getProperty("ga.excluded-reason", "")})"
            )
        }
        verdict.startSampling()
        health.start()
        evidence.start()
    }

    @AfterAll
    fun close() {
        health.stop()
        evidence.stop()
        verdict.stopSampling()
    }

    /**
     * Runs a scenario's [body] within its [budget], recording a failure in the report before JUnit sees it: a scenario
     * that runs out of time fails and lets the next one run, instead of running into the job's limit (F10).
     */
    @Suppress("TooGenericExceptionCaught") // Any failure is recorded, then thrown on.
    private fun scenario(scenario: String, name: String, budget: Duration = SCENARIO_BUDGET, body: () -> String) {
        val task = FutureTask(body)
        thread(name = "scenario-$name", isDaemon = true) { task.run() }
        val detail = try {
            task.get(budget.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timedOut: TimeoutException) {
            task.cancel(true)
            report.check(scenario, name, false, "exceeded its budget of $budget")
            throw AssertionError("$scenario / $name exceeded its budget of $budget", timedOut)
        } catch (failed: ExecutionException) {
            val failure = failed.cause ?: failed
            report.check(scenario, name, false, "${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        }
        report.check(scenario, name, true, detail)
    }

    @Test
    @Order(1)
    fun `the catalog and the stock are filed through the gateway`() = scenario("functional", "catalog and stock") {
        Deployment.PRODUCTS.forEach { (id, price) -> flows.product(id, "Wow $id", price) }
        Deployment.STOCK.forEach { (id, quantity) -> flows.receive(id, quantity) }
        "${Deployment.PRODUCTS.size} products published, ${Deployment.STOCK.size} stocks received"
    }

    @Test
    @Order(2)
    fun `a checkout goes through the 15 hops to a paid order, then is shipped`() = scenario(
        "functional",
        "full chain"
    ) {
        flows.addItem("alice", "mug", flows.price("mug"), 2)
        flows.addItem("alice", "cap", flows.price("cap"), 1)
        val result = flows.checkoutWaiting("alice", ShopFlows.FULL_CHAIN)
        result["status"].asString().assert().describedAs(result.toString()).isEqualTo("SUCCEEDED")
        result["hop"]["stage"].asString().assert().isEqualTo("COMMITTED")
        result["hop"]["context"].asString().assert().isEqualTo("eshop-ordering")
        result["function"]["name"].asString().assert().isEqualTo("PayOrder")
        flows.eventually("alice-1 paid") { flows.orderStatus("alice-1") == "PAID" }
        flows.ship("alice-1")
        flows.eventually("the reservations of alice-1 committed") {
            flows.stock("mug")["reservations"]["alice-1"] == null && flows.stock("cap")["reservations"]["alice-1"] == null
        }
        val events = flows.trace("/eshop-ordering/order/alice-1").flatMap { it.events }
        events.assert().containsExactly(
            "OrderSubmitted",
            "OrderAwaitingValidation",
            "OrderStockItemConfirmed",
            "OrderStockItemConfirmed",
            "OrderStockConfirmed",
            "ScheduleSet",
            "OrderPaid",
            "ScheduleCancelled",
            "OrderShipped",
        )
        "alice-1 paid in one Wow-Wait of 15 hops and shipped: ${events.joinToString(", ")}"
    }

    @Test
    @Order(3)
    fun `a declined payment ends the wait at the payment saga and cancels the order`() = scenario(
        "functional",
        "declined payment"
    ) {
        flows.addItem("erin", "tv", flows.price("tv"), 1)
        val ended = flows.checkoutWaiting("erin", ShopFlows.FULL_CHAIN)
        ended["status"].asString().assert().describedAs(ended.toString()).isEqualTo("SUCCEEDED")
        ended["hop"]["subscription"].asString().assert().isEqualTo("OrderPaymentSaga.onPaymentSucceeded")
        ended["chainEnded"].asBoolean().assert().isTrue()
        flows.eventually("erin-1 cancelled") { flows.orderStatus("erin-1") == "CANCELLED" }
        checkNotNull(flows.payment("erin-1"))["status"].asString().assert().isEqualTo("FAILED")
        "erin-1 declined over the gateway's limit: the wait ended at the payment saga, the order cancelled"
    }

    @Test
    @Order(4)
    fun `a stock too short cancels the order and releases its other reservation`() = scenario(
        "functional",
        "stock rejection"
    ) {
        flows.addItem("bob", "pad", flows.price("pad"), 1)
        flows.addItem("bob", "pen", flows.price("pen"), 3)
        flows.checkout("bob").status.assert().isEqualTo(ShopFlows.OK)
        flows.eventually("bob-1 cancelled") { flows.orderStatus("bob-1") == "CANCELLED" }
        checkNotNull(flows.order("bob-1"))["cancelReason"].asString().assert().contains("pen")
        // The compensation: the pad reserved for bob-1 is released.
        flows.eventually("the pad released") {
            flows.stock("pad")["available"].asInt() == Deployment.STOCK.getValue("pad") && flows.stock("pad")["reservations"]["bob-1"] == null
        }
        flows.stock("pen")["available"].asInt().assert().isEqualTo(Deployment.STOCK.getValue("pen"))
        flows.payment("bob-1").assert().isNull()
        "bob-1 cancelled for the pen, the pad's reservation released, no payment requested"
    }

    @Test
    @Order(5)
    fun `remote_ip is the client's address, not one the client claims`() = scenario("functional", "remote_ip") {
        val outsider = checkNotNull(kube.pods("outsider").single().ip)
        val body = """{"productId":"mug","unitPrice":${flows.price("mug")},"quantity":1}"""
        val tokens = Tokens(System.getProperty("ga.secret", SECRET))
        // From a pod, whose address is known, with and without a claim of its own, which the gateway overwrites (D296).
        listOf("ivy" to null, "ivy-forged" to FORGED).forEach { (basket, claim) ->
            val headers = listOfNotNull(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer ${tokens.of(Who.buyer(basket))}",
                claim?.let { "X-Forwarded-For" to it },
            )
            val sent = network.http(
                "outsider",
                "cosec-gateway",
                80,
                "POST",
                "/eshop-basket/basket/$basket/add-basket-item",
                body,
                *headers.toTypedArray(),
            )
            sent.assert().describedAs(sent).startsWith("200")
        }
        // Through the node port, the runner's path: the gateway sees the kind node's address.
        flows.addItem("ivy-node-port", "mug", flows.price("mug"), 1).status.assert().isEqualTo(ShopFlows.OK)
        if (postgres == null) {
            report.gap(
                "functional",
                "remote_ip from the log",
                "F1",
                "the mongo arm reads no log rows; the requests were served"
            )
            return@scenario "sent from $outsider"
        }
        probes.serviceSideEvidence(body, tokens)
        val node = kube.require(
            "get",
            "nodes",
            "-o",
            "jsonpath={.items[0].status.addresses[?(@.type==\"InternalIP\")].address}",
        ).trim()
        val recorded = mapOf(
            "ivy" to probes.lastHeaders("ivy"),
            "ivy-forged" to probes.lastHeaders("ivy-forged"),
            "ivy-node-port" to probes.lastHeaders("ivy-node-port"),
        ).mapValues { (_, headers) -> headers["remote_ip"]?.asString() }
        report.note("remote_ip recorded: $recorded (outsider $outsider, node $node, claimed $FORGED)")
        recorded.assert().isEqualTo(mapOf("ivy" to outsider, "ivy-forged" to outsider, "ivy-node-port" to node))
        "remote_ip is the client's: the outsider's $outsider with and without a claimed $FORGED, the node's $node " +
            "through the node port"
    }

    @Test
    @Order(10)
    fun `the public ports are reachable through the gateway alone`() = scenario(
        "security",
        "public ports behind the gateway"
    ) {
        val blocked = Deployment.PUBLIC.filter { it.first !in excluded }.map { (service, port) ->
            val outsider = network.reach("outsider", service, port)
            val insider = network.reach("insider", service, port)
            check(outsider.startsWith("000")) { "the outsider reached $service's public port: $outsider" }
            check(
                insider.startsWith("000")
            ) { "a shop pod that is not the gateway reached $service's public port: $insider" }
            service
        }
        "blocked around the gateway: ${blocked.joinToString()}"
    }

    @Test
    @Order(11)
    fun `the internal ports are reachable from the platform's pods alone`() = scenario("security", "internal ports") {
        Deployment.INTERNAL.filter { it.first !in excluded }.forEach { (service, port) ->
            val outsider = network.reach("outsider", "$service-cluster", port)
            check(outsider.startsWith("000")) { "the outsider reached $service's internal port: $outsider" }
        }
        // The positive control: a pod of the shop reaches them, so the refusals are the policies'.
        Deployment.INTERNAL.filter { it.first.startsWith("eshop-") && it.first !in excluded }.forEach { (service, port) ->
            val insider = network.reach("insider", "$service-cluster", port)
            check(!insider.startsWith("000")) { "the shop's own pod did not reach $service's internal port: $insider" }
        }
        "outsider refused on ${Deployment.INTERNAL.size} internal ports; the shop's pod served on the eShop's"
    }

    @Test
    @Order(12)
    fun `Wow-Service grants nothing and Wow-Call is refused on the public port`() = scenario(
        "security",
        "Wow-Service, Wow-Call"
    ) {
        val mallory = Who.buyer("mallory")
        val service = mapOf("Wow-Service" to "eshop-basket-service")
        // A service's name does not open alice's order to mallory.
        val read = shop.get("/eshop-ordering/order/alice-1/state", mallory, service)
        check(read.status != ShopFlows.OK) { "mallory read alice-1 naming a service: ${read.status}" }
        // A call is taken on the internal port alone (D208), whatever the request carries.
        val call = shop.post(
            "/eshop-ordering/order/mallory-x/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":1}]}""",
            mallory,
            service + ("Wow-Call" to "{}"),
        )
        call.status.assert().describedAs(call.body).isEqualTo(FORBIDDEN)
        val anonymous = shop.get("/eshop-ordering/order/alice-1/state", null)
        anonymous.status.assert().describedAs(anonymous.body).isEqualTo(UNAUTHORIZED)
        "mallory with Wow-Service: ${read.status}; Wow-Call: ${call.status}; no token at the gateway: ${anonymous.status}"
    }

    @Test
    @Order(13)
    fun `the shop's nodes reach each other's internal ports, within and across services`() =
        scenario("security", "internal mesh (steady)") { network.mesh("steady") }

    @Test
    @Order(20)
    fun `a parked payment reaches ops and is resumed there`() = scenario("ops", "parked, resumed from ops") {
        val started = System.currentTimeMillis()
        ops.outage(true)
        flows.addItem("olga", "mug", flows.price("mug"), 1)
        flows.checkout("olga").status.assert().isEqualTo(ShopFlows.OK)
        val id = Ledger.parkedId(
            "eshop-payment-service",
            GATEWAY_SAGA,
            EshopPaymentContract.Payment.ref(Caller.DEFAULT_TENANT, "olga-1")
        )
        flows.eventually("the parked payment listed by ops", OPS_BOUND) { ops.exhausted(started).contains(id) }
        val received = System.currentTimeMillis() - started
        flows.orderStatus("olga-1").assert().isEqualTo("STOCK_CONFIRMED")
        shop.post("/ops/parked/$id/resume", "{}", Who.VIEWER).status.assert().isEqualTo(FORBIDDEN)
        ops.outage(false)
        with(flows) {
            shop.post(
                "/ops/parked/$id/resume",
                """{"reason":"The gateway is back."}""",
                Who.OPERATOR
            ).committed("resume $id")
        }
        flows.eventually(
            "the entry resolved in ops",
            OPS_BOUND
        ) { ops.entry(id)?.get("status")?.asString() == "RESOLVED" }
        flows.eventually("olga-1 paid") { flows.orderStatus("olga-1") == "PAID" }
        ops.exhausted(started).assert().doesNotContain(id)
        if (postgres != null) {
            verdict.connect("payment").use { SoakDatabase(it, "payment").parked() }.keys
                .filter { "olga-1" in it }.assert().isEmpty()
        }
        "parked, seen in ops after $received ms, resumed by an operator, resolved and paid; the ledger entry is gone"
    }

    @Test
    @Order(21)
    fun `an order not paid in its grace period cancels itself by its timer`() = scenario(
        "functional",
        "grace period (timer)"
    ) {
        val started = System.currentTimeMillis()
        ops.outage(true)
        flows.addItem("gus", "mug", flows.price("mug"), 1)
        flows.checkout("gus").status.assert().isEqualTo(ShopFlows.OK)
        val id = Ledger.parkedId(
            "eshop-payment-service",
            GATEWAY_SAGA,
            EshopPaymentContract.Payment.ref(Caller.DEFAULT_TENANT, "gus-1")
        )
        flows.eventually("the parked payment of gus-1 in ops", OPS_BOUND) { ops.exhausted(started).contains(id) }
        flows.eventually(
            "gus-1 cancelled by its grace period",
            GRACE.plusSeconds(90)
        ) { flows.orderStatus("gus-1") == "CANCELLED" }
        val cancelled = System.currentTimeMillis() - started
        ops.outage(false)
        // The charge the gateway never made is skipped: the order it was for is cancelled.
        with(flows) {
            shop.post(
                "/ops/parked/$id/skip",
                """{"reason":"Its order was cancelled."}""",
                Who.OPERATOR
            ).committed("skip $id")
        }
        flows.eventually(
            "the entry of gus-1 resolved in ops",
            OPS_BOUND
        ) { ops.entry(id)?.get("status")?.asString() == "RESOLVED" }
        val events = flows.trace("/eshop-ordering/order/gus-1").flatMap { it.events }
        events.assert().contains("ScheduleSet")
        "gus-1 cancelled ${cancelled / MILLIS} s after its checkout (grace ${GRACE.seconds} s): ${events.joinToString(
            ", "
        )}"
    }

    @Test
    @Order(30)
    fun `scaling the ordering 2 to 3 to 2 rebalances its partitions`() = scenario("cluster", "scale 2→3→2") {
        val load = Load(flows).start()
        try {
            listOf(3, 2).forEach { replicas ->
                Outages.measure(
                    report,
                    "scale eshop-ordering to $replicas",
                    SCALE_BOUND,
                    "checkout to the ordering",
                    probes::probeToOrdering,
                    settled = { kube.pods("eshop-ordering").count { it.ready && !it.deleting } == replicas && probes.rebalanced(replicas) },
                ) { kube.scale("eshop-ordering", replicas) }
            }
        } finally {
            load.stop()
        }
        "scaled up and down under load (${load.sent} checkouts sent); leases spread over the nodes each time"
    }

    @Test
    @Order(31)
    fun `a rolling restart of the ordering loses nothing`() = scenario("cluster", "rolling restart") {
        val load = Load(flows).start()
        try {
            Outages.measure(
                report,
                "rolling restart of eshop-ordering",
                ROLLING_BOUND,
                "checkout to the ordering",
                probes::probeToOrdering,
                settled = { kube.awaitRollout("deployment/eshop-ordering") },
            ) { kube.rolloutRestart("eshop-ordering") }
        } finally {
            load.stop()
        }
        "restarted under load (${load.sent} checkouts sent)"
    }

    @Test
    @Order(32)
    fun `deleting the pod that owns an order's partition mid-checkout loses no effect`() =
        scenario(
            "cluster",
            "partition owner deleted mid-checkout",
            SCENARIO_BUDGET.multipliedBy(OWNER_DELETIONS.toLong())
        ) {
            // ga.owner-deletions rounds (1 by default), each a buyer of its own: repeated crashes leave their
            // PostgreSQL backends behind (F10).
            (1..OWNER_DELETIONS).joinToString(
                "; "
            ) { round -> deleteOwnerMidCheckout(if (round == 1) "carol" else "carol$round") }
        }

    /** Force-deletes the owner of [buyer]'s first order's partition mid-checkout; the order is paid once and shipped. */
    private fun deleteOwnerMidCheckout(buyer: String): String {
        val order = "$buyer-1"
        flows.addItem(buyer, "kite", flows.price("kite"), 2)
        val owner = postgres?.let { probes.partitionOwner(order) } ?: kube.pods("eshop-ordering").first().name
        if (postgres == null) {
            report.note(
                "mongo arm: the lease store is MongoDB; deleted the first ordering pod, $owner"
            )
        }
        val load = Load(flows).start()
        try {
            Outages.measure(
                report,
                "delete the owner of $order's partition ($owner)",
                TAKEOVER_BOUND,
                "checkout to the ordering",
                probes::probeToOrdering,
                settled = { kube.pods("eshop-ordering").count { it.ready && !it.deleting } == 2 },
            ) {
                val checkout = thread { flows.checkoutWaiting(buyer, ShopFlows.TO_ORDERING) }
                Thread.sleep(MID_CHECKOUT.toMillis())
                report.note("force-deleting $owner, at ${kube.pods("eshop-ordering").find { it.name == owner }?.ip}")
                kube.deletePod(owner, force = true)
                checkout.join()
            }
        } finally {
            load.stop()
        }
        flows.eventually("$order paid after the deletion", TAKEOVER_BOUND.multipliedBy(2)) {
            flows.orderStatus(order) == "PAID"
        }
        flows.ship(order)
        flows.trace("/eshop-payment/payment/$order").flatMap { it.events }
            .assert().containsExactly("PaymentRequested", "PaymentSucceeded")
        return "$owner deleted mid-checkout; $order paid once and shipped"
    }

    @Test
    @Order(33)
    fun `a Kafka broker restart is absorbed`() = scenario("cluster", "Kafka broker restart") {
        val load = Load(flows).start()
        try {
            Outages.measure(
                report,
                "restart the Kafka broker",
                KAFKA_BOUND,
                "checkout through the full chain",
                probes::probeFullChain,
                settled = { kube.pods("kafka").all { it.ready && !it.deleting } && kube.awaitRollout("statefulset/kafka") },
            ) { kube.deletePod("kafka-0") }
        } finally {
            load.stop()
        }
        "kafka-0 restarted under load (${load.sent} checkouts sent)"
    }

    @Test
    @Order(34)
    fun `a MongoDB primary step-down is absorbed`() {
        if (backend != "mongo") return
        scenario("cluster", "MongoDB primary step-down") {
            val primary = kube.exec("mongo-0", "mongosh", "--quiet", "--eval", "rs.isMaster().primary").out.trim()
            val pod = primary.substringBefore('.')
            val load = Load(flows).start()
            try {
                Outages.measure(
                    report,
                    "step down the primary $pod",
                    STEP_DOWN_BOUND,
                    "checkout to the ordering",
                    probes::probeToOrdering,
                    settled = {
                        val now = kube.exec(
                            "mongo-0",
                            "mongosh",
                            "--quiet",
                            "--eval",
                            "rs.isMaster().primary"
                        ).out.trim()
                        now.isNotEmpty() && now != primary
                    },
                ) { kube.exec(pod, "mongosh", "--quiet", "--eval", "rs.stepDown(30)") }
            } finally {
                load.stop()
            }
            "the primary $pod stepped down under load (${load.sent} checkouts sent)"
        }
    }

    @Test
    @Order(39)
    fun `after the faults the shop's nodes still reach each other's internal ports`() =
        scenario("cluster", "internal mesh (after faults)") { network.mesh("after faults") }

    @Test
    @Order(40)
    fun `the probes stayed green outside the faults and nothing restarted`() = scenario("observability", "probes") {
        health.stop()
        val unexpected = health.unreadyOutsideFaults()
        val restarted = kube.allPods().filter { it.restarts > 0 }
        check(unexpected.isEmpty()) { "not ready outside a fault: ${unexpected.take(LISTED)}" }
        check(
            restarted.isEmpty()
        ) { "restarted (liveness or crash): ${restarted.map { "${it.name} ×${it.restarts}" }}" }
        "${health.samples} samples, every pod ready outside the faults, no container restarted; ${health.load()}"
    }

    @Test
    @Order(41)
    fun `the key metrics are served`() {
        val pod = kube.pods("eshop-ordering").first { it.ready }.name
        val metrics = kube.run("get", "--raw", "/api/v1/namespaces/default/pods/$pod:8083/proxy/actuator/metrics")
        val names = if (metrics.ok) json.readTree(metrics.out)["names"].values().map { it.asString() } else emptyList()
        val wow = names.filter { it.startsWith("wow.") }
        if (wow.isEmpty()) {
            report.gap(
                "observability",
                "key metrics",
                "F6",
                "$pod serves ${names.size} metrics, none of Wow's (the eShop's servers have no wow-opentelemetry): " +
                    (if (metrics.ok) names.take(LISTED).joinToString() else metrics.err.take(ShopFlows.BODY)),
            )
        } else {
            report.check("observability", "key metrics", true, "$pod: ${wow.joinToString()}")
        }
    }

    @Test
    @Order(42)
    fun `the logs hold no unexpected ERROR`() = scenario("observability", "no unexpected ERROR") {
        val logs = LogScan(kube, report.faults)
        val unexpected = logs.unexpectedErrors()
        File(System.getProperty("ga.report", "build/ga-e2e"), "errors.txt").apply { parentFile.mkdirs() }
            .writeText((logs.allowed + unexpected).joinToString("\n"))
        check(
            unexpected.isEmpty()
        ) { "${unexpected.size} unexpected ERROR lines, the first: ${unexpected.take(LISTED)}" }
        "${logs.scanned} pods scanned; ${logs.allowed.size} ERROR lines inside fault windows, allowed"
    }

    @Test
    @Order(99)
    fun `the soak invariants hold with zero violations`() {
        // Bounded: a shop that does not recover would keep the reads timing out for hours, and the report unwritten.
        var failure: Throwable? = null
        val judging = thread(name = "verdict", isDaemon = true) {
            runCatching {
                verdict.settle()
                verdict.judge()
            }.onFailure { failure = it }
        }
        judging.join(VERDICT_BUDGET.toMillis())
        if (judging.isAlive) {
            report.check(
                "verdict",
                "judged in time",
                false,
                "not judged within $VERDICT_BUDGET: the shop did not answer"
            )
        }
        failure?.let { report.check("verdict", "judged", false, "${it.javaClass.simpleName}: ${it.message}") }
        val passed = report.write(verdict.all)
        verdict.all.filter { it.violated > 0 }.forEach {
            println(
                "[ga-e2e] violated ${it.name}: ${it.describe()["evidence"]}"
            )
        }
        report.failed.map { "${it.scenario} / ${it.name}: ${it.detail}" }.assert().isEmpty()
        verdict.all.sumOf { it.violated }.assert().isEqualTo(0L)
        passed.assert().isTrue()
    }

    private companion object {
        const val FORGED = "203.0.113.9"
        val VERDICT_BUDGET: Duration = Duration.ofMinutes(15)
        val SCENARIO_BUDGET: Duration = Duration.ofMinutes(15)
        val OWNER_DELETIONS: Int = System.getProperty("ga.owner-deletions", "1").toInt()
        const val SECRET = "ga-e2e-not-a-secret-only-for-the-kind-cluster-0123456789"
        const val FORBIDDEN = 403
        const val UNAUTHORIZED = 401
        const val MILLIS = 1000
        const val LISTED = 10
        const val GATEWAY_SAGA = "PaymentGatewaySaga.onPaymentRequested"

        /** The ordering's payment grace period in this deployment (services/eshop-ordering/config.yaml). */
        val GRACE: Duration = Duration.ofSeconds(120)
        val OPS_BOUND: Duration = Duration.ofSeconds(90)
        val MID_CHECKOUT: Duration = Duration.ofMillis(150)
        val SCALE_BOUND: Duration = Duration.ofSeconds(30)
        val ROLLING_BOUND: Duration = Duration.ofSeconds(60)
        val TAKEOVER_BOUND: Duration = Duration.ofSeconds(120)
        val KAFKA_BOUND: Duration = Duration.ofSeconds(120)
        val STEP_DOWN_BOUND: Duration = Duration.ofSeconds(60)
    }
}
