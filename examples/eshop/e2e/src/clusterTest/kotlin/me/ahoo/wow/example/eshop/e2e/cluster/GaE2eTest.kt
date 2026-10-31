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
 *    rejection and its compensation, the payment grace period (a timer), `remote_ip` recorded as the client's, and a
 *    command's request id from the gateway, kept across its retry (D328).
 * 2. Security, negative: the public and internal ports unreachable around the gateway, `Wow-Service` granting nothing
 *    on the public port and `Wow-Call` refused there.
 * 3. The ops loop: a parked payment reaches ops, an operator retries it there, and the entry is gone.
 * 4. Cluster behaviour, each fault's unavailability window measured and bounded: the ordering scaled 2→3→2, a rolling
 *    restart, the pod owning an order's partition deleted mid-checkout, a Kafka broker restart and, on the mongo arm,
 *    the primary stepping down, and Elasticsearch, the products' search, scaled to zero and back.
 * 5. Observability: probes green outside the faults, no restarts, the key metrics, no unexpected ERROR in the logs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("FunctionNaming") // detekt names test functions so only under src/test; this is src/clusterTest.
class GaE2eTest {
    private val backend = System.getProperty("ga.backend", "postgres")
    private val report = ClusterReport(File(System.getProperty("ga.report", "build/ga-e2e")), backend)
    private val kube = Kube { println("[ga-e2e] $it") }

    /** The number of alice's paid order, which the security scenario reads as mallory. */
    @Volatile
    private var aliceOrder = "SO-000000-0"

    private val flows = ShopFlows(
        Shop(
            System.getProperty("ga.gateway", "http://localhost:30080"),
            Tokens(System.getProperty("ga.secret", SECRET))
        ),
    )
    private val shop = flows.shop

    /** The arm's store, read for the verdict: PostgreSQL through its node port, or MongoDB through mongosh. */
    private val reads = StoreReads.of(
        backend,
        kube,
        System.getProperty("ga.postgres", "jdbc:postgresql://localhost:30432")
    )
    private val verdict = ClusterVerdict(flows, reads) { println("[ga-e2e] $it") }
    private val dumps = ThreadDumps(kube, File(System.getProperty("ga.report", "build/ga-e2e"), "threads")) {
        println("[ga-e2e] $it")
    }
    private val health = HealthWatch(kube, report, dumps)
    private val network = PodNetwork(kube, report)
    private val ops = OpsDesk(kube, report, shop)
    private val evidence = Evidence(kube, File(System.getProperty("ga.report", "build/ga-e2e"), "evidence"))
    private val partitions = System.getProperty("ga.partitions", "8").toInt()

    /**
     * The services this run left out of the deployment (`ga.excluded`, by their directories in deploy/ or their
     * services' names), for the reason its report states.
     */
    private val excluded = System.getProperty("ga.excluded", "").split(' ', ',').filter { it.isNotBlank() }
        .map { if (it.endsWith(SERVICE)) it else "$it$SERVICE" }.toSet()
    private val probes = ClusterProbes(kube, report, flows, reads, network, partitions)
    private val requestIds = RequestIds(kube, flows)
    private val search = SearchStore(kube, network, flows, report)

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
            dumps.dumpAll("$scenario $name timed out")
            throw AssertionError("$scenario / $name exceeded its budget of $budget", timedOut)
        } catch (failed: ExecutionException) {
            val failure = failed.cause ?: failed
            report.check(scenario, name, false, "${failure.javaClass.simpleName}: ${failure.message}")
            dumps.dumpAll("$scenario $name failed")
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
        result["status"].asString().assert().describedAs(result.toString()).isEqualTo("succeeded")
        result["hop"]["stage"].asString().assert().isEqualTo("committed")
        result["hop"]["context"].asString().assert().isEqualTo("eshop-ordering")
        result["function"]["name"].asString().assert().isEqualTo("PayOrder")
        val number = flows.numberOf(result)
        aliceOrder = number
        flows.eventually("$number paid") { flows.orderStatus(number) == "PAID" }
        flows.ship(number)
        flows.eventually("the reservations of $number committed") {
            flows.stock("mug")["reservations"][number] == null && flows.stock("cap")["reservations"][number] == null
        }
        val events = flows.trace("/eshop-ordering/order/$number").flatMap { it.events }
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
        "$number paid in one Wow-Wait of 15 hops and shipped: ${events.joinToString(", ")}"
    }

    @Test
    @Order(3)
    fun `a declined payment ends the wait at the payment saga and cancels the order`() = scenario(
        "functional",
        "declined payment"
    ) {
        flows.addItem("erin", "tv", flows.price("tv"), 1)
        val ended = flows.checkoutWaiting("erin", ShopFlows.FULL_CHAIN)
        ended["status"].asString().assert().describedAs(ended.toString()).isEqualTo("succeeded")
        ended["hop"]["subscription"].asString().assert().isEqualTo("OrderPaymentSaga.onPaymentSucceeded")
        ended["chainEnded"].asBoolean().assert().isTrue()
        // The payment saga's hop names the payment, whose id is its order's number.
        val number = flows.numberOf(ended)
        flows.eventually("$number cancelled") { flows.orderStatus(number) == "CANCELLED" }
        checkNotNull(flows.payment(number))["status"].asString().assert().isEqualTo("FAILED")
        "$number declined over the gateway's limit: the wait ended at the payment saga, the order cancelled"
    }

    @Test
    @Order(4)
    fun `a stock too short cancels the order and releases its other reservation`() = scenario(
        "functional",
        "stock rejection"
    ) {
        flows.addItem("bob", "pad", flows.price("pad"), 1)
        flows.addItem("bob", "pen", flows.price("pen"), 3)
        val number = flows.numberOf(flows.checkoutWaiting("bob", ShopFlows.TO_ORDERING))
        flows.eventually("$number cancelled") { flows.orderStatus(number) == "CANCELLED" }
        checkNotNull(flows.order(number))["cancelReason"].asString().assert().contains("pen")
        // The compensation: the pad reserved for $number is released.
        flows.eventually("the pad released") {
            flows.stock("pad")["available"].asInt() == Deployment.STOCK.getValue("pad") && flows.stock("pad")["reservations"][number] == null
        }
        flows.stock("pen")["available"].asInt().assert().isEqualTo(Deployment.STOCK.getValue("pen"))
        flows.payment(number).assert().isNull()
        "$number cancelled for the pen, the pad's reservation released, no payment requested"
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
                "/eshop-basket/basket/add-basket-item",
                body,
                *headers.toTypedArray(),
            )
            sent.assert().describedAs(sent).startsWith("200")
        }
        // Through the node port, the runner's path: the gateway sees the kind node's address.
        flows.addItem("ivy-node-port", "mug", flows.price("mug"), 1).status.assert().isEqualTo(ShopFlows.OK)
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
    @Order(6)
    fun `a request the gateway retries keeps its CoSec-Request-Id`() = scenario(
        "functional",
        "request id across a retry (D328)"
    ) { requestIds.acrossRetry() }

    @Test
    @Order(7)
    fun `a command without Wow-Request-Id takes the gateway's CoSec-Request-Id`() = scenario(
        "functional",
        "request id from the gateway (D328)"
    ) { requestIds.fallback() }

    @Test
    @Order(9)
    fun `the basket finds the ordering's nodes by DNS, with no address set`() = scenario(
        "functional",
        "peers by DNS (D305)",
    ) {
        // No wow.peers.eshop-ordering.url anywhere in the basket's configuration: its checkout saga reaches the
        // ordering's internal port through the SRV records of eshop-ordering-service-cluster, named after the service
        // the ordering's api contract names (decision D330).
        val config = kube.require("get", "configmap", "eshop-basket-service-config", "-o", "jsonpath={.data}")
        check("peers" !in config) { "the basket's configuration sets a peer: $config" }
        check(probes.probeToOrdering()) { "a checkout did not reach the ordering through the basket's saga" }
        "the basket's checkout saga reached the ordering through the SRV records of eshop-ordering-service-cluster, " +
            "no peer address set"
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
        val read = shop.get("/eshop-ordering/order/$aliceOrder/state", mallory, service)
        check(read.status != ShopFlows.OK) { "mallory read $aliceOrder naming a service: ${read.status}" }
        // A call is taken on the internal port alone (D208), whatever the request carries.
        val call = shop.post(
            "/eshop-ordering/order/mallory-x/submit-order",
            """{"items":[{"productId":"mug","unitPrice":8.50,"quantity":1}]}""",
            mallory,
            service + ("Wow-Call" to "{}"),
        )
        call.status.assert().describedAs(call.body).isEqualTo(FORBIDDEN)
        val anonymous = shop.get("/eshop-ordering/order/$aliceOrder/state", null)
        anonymous.status.assert().describedAs(anonymous.body).isEqualTo(UNAUTHORIZED)
        "mallory with Wow-Service: ${read.status}; Wow-Call: ${call.status}; no token at the gateway: ${anonymous.status}"
    }

    @Test
    @Order(13)
    fun `the shop's nodes reach each other's internal ports, within and across services`() =
        scenario("security", "internal mesh (steady)") { network.mesh("steady") }

    @Test
    @Order(20)
    fun `a parked payment reaches ops and is retried there`() = scenario("ops", "parked, retried from ops") {
        val started = System.currentTimeMillis()
        ops.outage(true)
        flows.addItem("olga", "mug", flows.price("mug"), 1)
        val number = flows.numberOf(flows.checkoutWaiting("olga", ShopFlows.TO_ORDERING))
        val id = Ledger.parkedId(
            "eshop-payment-service",
            "eshop-payment",
            GATEWAY_SAGA,
            EshopPaymentContract.Payment.ref(Caller.DEFAULT_TENANT, number)
        )
        flows.eventually("the parked payment listed by ops", OPS_BOUND) { ops.exhausted(started).contains(id) }
        val received = System.currentTimeMillis() - started
        flows.orderStatus(number).assert().isEqualTo("STOCK_CONFIRMED")
        shop.post("/ops/parked/$id/retry", "{}", Who.VIEWER).status.assert().isEqualTo(FORBIDDEN)
        ops.outage(false)
        with(flows) {
            command(
                "/ops/parked/$id/retry",
                """{"reason":"The gateway is back."}""",
                Who.OPERATOR
            ).committed("retry $id")
        }
        flows.eventually(
            "the entry resolved in ops",
            OPS_BOUND
        ) { ops.entry(id)?.get("status")?.asString() == "resolved" }
        flows.eventually("$number paid") { flows.orderStatus(number) == "PAID" }
        ops.exhausted(started).assert().doesNotContain(id)
        reads.parked("payment").filter { it["aggregateId"] == number }.assert().isEmpty()
        "parked, seen in ops after $received ms, retried by an operator, resolved and paid; the ledger entry is gone"
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
        val number = flows.numberOf(flows.checkoutWaiting("gus", ShopFlows.TO_ORDERING))
        val id = Ledger.parkedId(
            "eshop-payment-service",
            "eshop-payment",
            GATEWAY_SAGA,
            EshopPaymentContract.Payment.ref(Caller.DEFAULT_TENANT, number)
        )
        flows.eventually("the parked payment of $number in ops", OPS_BOUND) { ops.exhausted(started).contains(id) }
        flows.eventually(
            "$number cancelled by its grace period",
            GRACE.plusSeconds(90)
        ) { flows.orderStatus(number) == "CANCELLED" }
        val cancelled = System.currentTimeMillis() - started
        ops.outage(false)
        // The charge the gateway never made is skipped: the order it was for is cancelled.
        with(flows) {
            command(
                "/ops/parked/$id/skip",
                """{"reason":"Its order was cancelled."}""",
                Who.OPERATOR
            ).committed("skip $id")
        }
        flows.eventually(
            "the entry of $number resolved in ops",
            OPS_BOUND
        ) { ops.entry(id)?.get("status")?.asString() == "resolved" }
        val events = flows.trace("/eshop-ordering/order/$number").flatMap { it.events }
        events.assert().contains("ScheduleSet")
        "$number cancelled ${cancelled / MILLIS} s after its checkout (grace ${GRACE.seconds} s): ${events.joinToString(
            ", "
        )}"
    }

    @Test
    @Order(22)
    fun `the products' search is served by Elasticsearch`() {
        if (backend != "mongo") return
        scenario("functional", "product search on Elasticsearch (D329)") { search.served() }
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
                    settled = {
                        kube.pods(Deployment.ORDERING).count { it.ready && !it.deleting } == replicas && probes.rebalanced(replicas)
                    },
                ) { kube.scale(Deployment.ORDERING, replicas) }
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
                settled = { kube.awaitRollout("deployment/${Deployment.ORDERING}") },
            ) { kube.rolloutRestart(Deployment.ORDERING) }
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

    /**
     * Force-deletes the owner of [buyer]'s order's partition mid-checkout; the order is paid once and shipped. The
     * ordering numbers the order, so its partition, and the pod that owns it, are known once the checkout answers at
     * the order's commit: the pod is deleted then, while the order process goes on (the reservation, the payment).
     */
    private fun deleteOwnerMidCheckout(buyer: String): String {
        flows.addItem(buyer, "kite", flows.price("kite"), 2)
        var order = ""
        var owner = ""
        val load = Load(flows).start()
        try {
            Outages.measure(
                report,
                "delete the owner of $buyer's order's partition",
                TAKEOVER_BOUND,
                "checkout to the ordering",
                probes::probeToOrdering,
                settled = { kube.pods(Deployment.ORDERING).count { it.ready && !it.deleting } == 2 },
            ) {
                order = flows.numberOf(flows.checkoutWaiting(buyer, ShopFlows.TO_ORDERING))
                owner = probes.partitionOwner(order)
                report.note(
                    "force-deleting $owner, the owner of $order's partition, at " +
                        "${kube.pods(Deployment.ORDERING).find { it.name == owner }?.ip}",
                )
                kube.deletePod(owner, force = true)
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
            val primary = kube.exec("mongodb-0", "mongosh", "--quiet", "--eval", "rs.isMaster().primary").out.trim()
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
                            "mongodb-0",
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
    @Order(35)
    fun `an Elasticsearch outage leaves the products' commands and states alone`() {
        if (backend != "mongo") return
        scenario("cluster", "Elasticsearch outage") { search.outage() }
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
        val restarted = kube.allPods().filter { platform(it.name) && it.restarts > 0 }
        check(unexpected.isEmpty()) { "not ready outside a fault: ${unexpected.take(LISTED)}" }
        check(
            restarted.isEmpty()
        ) { "restarted (liveness or crash): ${restarted.map { "${it.name} ×${it.restarts}" }}" }
        "${health.samples} samples, every pod ready outside the faults, no container restarted; ${health.load()}"
    }

    @Test
    @Order(41)
    fun `the key metrics are served`() = scenario("observability", "key metrics") {
        // Every service's Wow meters, as the OpenTelemetry Java agent serves them on each pod (F6, services/*), read
        // from inside the pod; the ordering's commits and subscriptions among them.
        val served = Deployment.PUBLIC.map { it.first }.filter { it !in excluded }.associateWith { service ->
            val pod = kube.pods(service).first { it.ready && !it.deleting }.name
            network.http(pod, "localhost", METRICS_PORT, "GET", "/metrics").lineSequence()
                .filter { it.startsWith("wow_") }.map { it.substringBefore('{').substringBefore(' ') }.toSet()
        }
        val silent = served.filterValues { it.isEmpty() }.keys
        check(silent.isEmpty()) { "no Wow meter served by $silent" }
        val ordering = served.getValue(Deployment.ORDERING)
        listOf("wow_command_duration", "wow_subscription_").forEach { meter ->
            check(ordering.any { it.startsWith(meter) }) { "the ordering serves no $meter*: ${ordering.take(LISTED)}" }
        }
        served.entries.joinToString("; ") { (service, meters) -> "$service ${meters.size}" } + " Wow series; " +
            "the ordering's: ${ordering.filter { it.startsWith("wow_command") || it.startsWith("wow_subscription") }.take(LISTED)}"
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
    @Order(43)
    fun `no WARN repeats as a storm outside the faults`() = scenario("observability", "no WARN storm") {
        val storms = LogScan(kube, report.faults).warnStorms()
        File(System.getProperty("ga.report", "build/ga-e2e"), "warn-storms.txt").apply { parentFile.mkdirs() }
            .writeText(storms.joinToString("\n"))
        check(storms.isEmpty()) { "${storms.size} WARN storms, the first: ${storms.take(LISTED)}" }
        "no WARN repeated more than 30 times a minute outside the faults"
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
        const val METRICS_PORT = 9464
        const val SERVICE = "-service"
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
        val SCALE_BOUND: Duration = Duration.ofSeconds(30)
        val ROLLING_BOUND: Duration = Duration.ofSeconds(60)
        val TAKEOVER_BOUND: Duration = Duration.ofSeconds(120)
        val KAFKA_BOUND: Duration = Duration.ofSeconds(120)
        val STEP_DOWN_BOUND: Duration = Duration.ofSeconds(60)
    }
}
