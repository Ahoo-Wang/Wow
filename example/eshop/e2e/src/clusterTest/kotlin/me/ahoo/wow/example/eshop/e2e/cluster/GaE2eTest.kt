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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.example.eshop.e2e.json
import me.ahoo.wow.example.eshop.e2e.soak.SoakDatabase
import me.ahoo.wow.example.eshop.ordering.api.order.EshopOrderingContract
import me.ahoo.wow.example.eshop.payment.api.payment.EshopPaymentContract
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
    private val probes = AtomicInteger()

    init {
        verdict.startSampling()
        health.start()
    }

    @AfterAll
    fun close() {
        health.stop()
        verdict.stopSampling()
    }

    /** Runs a scenario's [body], recording a failure in the report before JUnit sees it. */
    @Suppress("TooGenericExceptionCaught") // Any failure is recorded, then thrown on.
    private fun scenario(scenario: String, name: String, body: () -> String) {
        val detail = try {
            body()
        } catch (failure: Throwable) {
            report.check(scenario, name, false, "${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        }
        report.check(scenario, name, true, detail)
    }

    @Test
    @Order(1)
    fun `the catalog and the stock are filed through the gateway`() = scenario("functional", "catalog and stock") {
        PRODUCTS.forEach { (id, price) -> flows.product(id, "Wow $id", price) }
        STOCK.forEach { (id, quantity) -> flows.receive(id, quantity) }
        "${PRODUCTS.size} products published, ${STOCK.size} stocks received"
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
            flows.stock("pad")["available"].asInt() == STOCK.getValue("pad") && flows.stock("pad")["reservations"]["bob-1"] == null
        }
        flows.stock("pen")["available"].asInt().assert().isEqualTo(STOCK.getValue("pen"))
        flows.payment("bob-1").assert().isNull()
        "bob-1 cancelled for the pen, the pad's reservation released, no payment requested"
    }

    @Test
    @Order(5)
    fun `remote_ip is the client's address, not one the client claims`() = scenario("functional", "remote_ip") {
        val outsider = checkNotNull(kube.pods("outsider").single().ip)
        // From a pod, so the client's address is known: the gateway's node port would show the node's.
        val body = """{"productId":"mug","unitPrice":${flows.price("mug")},"quantity":1}"""
        val sent = http(
            "outsider",
            "cosec-gateway",
            80,
            "POST",
            "/eshop-basket/basket/ivy/add-basket-item",
            body,
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${Tokens(System.getProperty("ga.secret", SECRET)).of(Who.buyer("ivy"))}",
            "X-User" to "ivy",
            // A client's own claim, which the gateway overwrites (D296).
            "X-Forwarded-For" to "203.0.113.9",
        )
        sent.assert().describedAs(sent).startsWith("200")
        if (postgres == null) {
            report.gap(
                "functional",
                "remote_ip from the log",
                "F1",
                "the mongo arm reads no log rows; the request was served"
            )
            return@scenario "sent from $outsider"
        }
        val recorded = verdict.connect("basket").use { connection ->
            val table = SoakDatabase(connection, "basket").logTables().getValue("basket")
            connection.prepareStatement(
                "SELECT headers FROM \"$table\" WHERE aggregate_id = ? ORDER BY version DESC LIMIT 1"
            )
                .use { statement ->
                    statement.setString(1, "ivy")
                    statement.executeQuery().use { rows ->
                        rows.next()
                        json.readTree(rows.getString(1))
                    }
                }
        }
        recorded["remote_ip"]?.asString().assert().describedAs(recorded.toString()).isEqualTo(outsider)
        "the commit recorded remote_ip $outsider, the client pod's, not the claimed 203.0.113.9"
    }

    @Test
    @Order(10)
    fun `the public ports are reachable through the gateway alone`() = scenario(
        "security",
        "public ports behind the gateway"
    ) {
        val blocked = PUBLIC.map { (service, port) ->
            val outsider = reach("outsider", service, port)
            val insider = reach("insider", service, port)
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
        INTERNAL.forEach { (service, port) ->
            val outsider = reach("outsider", "$service-cluster", port)
            check(outsider.startsWith("000")) { "the outsider reached $service's internal port: $outsider" }
        }
        // The positive control: a pod of the shop reaches them, so the refusals are the policies'.
        INTERNAL.filter { it.first.startsWith("eshop-") }.forEach { (service, port) ->
            val insider = reach("insider", "$service-cluster", port)
            check(!insider.startsWith("000")) { "the shop's own pod did not reach $service's internal port: $insider" }
        }
        "outsider refused on ${INTERNAL.size} internal ports; the shop's pod served on the eShop's"
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
    @Order(20)
    fun `a parked payment reaches ops and is resumed there`() = scenario("ops", "parked, resumed from ops") {
        val started = System.currentTimeMillis()
        outage(true)
        flows.addItem("olga", "mug", flows.price("mug"), 1)
        flows.checkout("olga").status.assert().isEqualTo(ShopFlows.OK)
        val id = Ledger.parkedId(
            "eshop-payment-service",
            GATEWAY_SAGA,
            EshopPaymentContract.Payment.ref(Caller.DEFAULT_TENANT, "olga-1")
        )
        flows.eventually("the parked payment listed by ops", OPS_BOUND) { exhausted(started).contains(id) }
        val received = System.currentTimeMillis() - started
        flows.orderStatus("olga-1").assert().isEqualTo("STOCK_CONFIRMED")
        shop.post("/ops/parked/$id/resume", "{}", Who.VIEWER).status.assert().isEqualTo(FORBIDDEN)
        outage(false)
        shop.post("/ops/parked/$id/resume", """{"reason":"The gateway is back."}""", Who.OPERATOR).let { resumed ->
            resumed.status.assert().describedAs(resumed.body).isEqualTo(ShopFlows.OK)
        }
        flows.eventually(
            "the entry resolved in ops",
            OPS_BOUND
        ) { opsEntry(id)?.get("status")?.asString() == "RESOLVED" }
        flows.eventually("olga-1 paid") { flows.orderStatus("olga-1") == "PAID" }
        exhausted(started).assert().doesNotContain(id)
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
        outage(true)
        flows.addItem("gus", "mug", flows.price("mug"), 1)
        flows.checkout("gus").status.assert().isEqualTo(ShopFlows.OK)
        val id = Ledger.parkedId(
            "eshop-payment-service",
            GATEWAY_SAGA,
            EshopPaymentContract.Payment.ref(Caller.DEFAULT_TENANT, "gus-1")
        )
        flows.eventually("the parked payment of gus-1 in ops", OPS_BOUND) { exhausted(started).contains(id) }
        flows.eventually(
            "gus-1 cancelled by its grace period",
            GRACE.plusSeconds(90)
        ) { flows.orderStatus("gus-1") == "CANCELLED" }
        val cancelled = System.currentTimeMillis() - started
        outage(false)
        // The charge the gateway never made is skipped: the order it was for is cancelled.
        shop.post("/ops/parked/$id/skip", """{"reason":"Its order was cancelled."}""", Who.OPERATOR).let { skipped ->
            skipped.status.assert().describedAs(skipped.body).isEqualTo(ShopFlows.OK)
        }
        flows.eventually(
            "the entry of gus-1 resolved in ops",
            OPS_BOUND
        ) { opsEntry(id)?.get("status")?.asString() == "RESOLVED" }
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
                    ::probeToOrdering,
                    settled = { kube.pods("eshop-ordering").count { it.ready && !it.deleting } == replicas && rebalanced(replicas) },
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
                ::probeToOrdering,
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
        scenario("cluster", "partition owner deleted mid-checkout") {
            flows.addItem("carol", "kite", flows.price("kite"), 2)
            val owner = postgres?.let { partitionOwner("carol-1") } ?: kube.pods("eshop-ordering").first().name
            if (postgres == null) {
                report.note(
                    "mongo arm: the lease store is MongoDB; deleted the first ordering pod, $owner"
                )
            }
            val load = Load(flows).start()
            try {
                Outages.measure(
                    report,
                    "delete the owner of carol-1's partition ($owner)",
                    TAKEOVER_BOUND,
                    "checkout to the ordering",
                    ::probeToOrdering,
                    settled = { kube.pods("eshop-ordering").count { it.ready && !it.deleting } == 2 },
                ) {
                    val checkout = thread { flows.checkoutWaiting("carol", ShopFlows.TO_ORDERING) }
                    Thread.sleep(MID_CHECKOUT.toMillis())
                    kube.deletePod(owner, force = true)
                    checkout.join()
                }
            } finally {
                load.stop()
            }
            flows.eventually(
                "carol-1 paid after the deletion",
                TAKEOVER_BOUND.multipliedBy(2)
            ) { flows.orderStatus("carol-1") == "PAID" }
            flows.ship("carol-1")
            flows.trace("/eshop-payment/payment/carol-1").flatMap { it.events }
                .assert().containsExactly("PaymentRequested", "PaymentSucceeded")
            "$owner deleted mid-checkout; carol-1 paid once and shipped"
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
                ::probeFullChain,
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
                    ::probeToOrdering,
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
    @Order(40)
    fun `the probes stayed green outside the faults and nothing restarted`() = scenario("observability", "probes") {
        health.stop()
        val unexpected = health.unreadyOutsideFaults()
        val restarted = kube.allPods().filter { it.restarts > 0 }
        check(unexpected.isEmpty()) { "not ready outside a fault: ${unexpected.take(LISTED)}" }
        check(
            restarted.isEmpty()
        ) { "restarted (liveness or crash): ${restarted.map { "${it.name} ×${it.restarts}" }}" }
        "${health.samples} samples, every pod ready outside the faults, no container restarted"
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
        verdict.settle()
        verdict.judge()
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

    /** A checkout until its order is committed in the ordering context: whether it was. */
    private fun probeToOrdering(): Boolean = probe(ShopFlows.TO_ORDERING, "10s")

    /** A checkout through the full chain, the inventory's Kafka hops included: whether its order was paid. */
    private fun probeFullChain(): Boolean = probe(ShopFlows.FULL_CHAIN, "20s")

    private fun probe(chain: String, timeout: String): Boolean {
        val buyer = "probe-${probes.incrementAndGet()}"
        if (flows.addItem(buyer, "sock", "5.00", 1).status != ShopFlows.OK) return false
        val answer = flows.checkout(buyer, mapOf(ShopFlows.WAIT to chain, ShopFlows.WAIT_TIMEOUT to timeout))
        return answer.status == ShopFlows.OK && json.readTree(answer.body)["status"]?.asString() == "SUCCEEDED"
    }

    /** Whether every one of the ordering's [replicas] nodes holds some of its command partitions' leases. */
    private fun rebalanced(replicas: Int): Boolean {
        if (postgres == null) return true
        val owners = verdict.connect("ordering").use { SoakDatabase(it, "ordering").leases() }
            .filterKeys { it.startsWith("$ORDERING_GROUP|") }
            .values.filter { (_, expires) -> expires > System.currentTimeMillis() }.map { it.first }.toSet()
        return owners.size == replicas
    }

    /** The node holding the ordering's command lease of [order]'s partition, once one does. */
    private fun partitionOwner(order: String): String {
        val partition = Partitioner.partitionOf(AggregateRef(ORDER, Caller.DEFAULT_TENANT, order), PARTITIONS)
        var owner: String? = null
        flows.eventually("an owner of partition $partition") {
            owner = verdict.connect("ordering").use { SoakDatabase(it, "ordering").leases() }["$ORDERING_GROUP|$partition"]
                ?.takeIf { (_, expires) -> expires > System.currentTimeMillis() }?.first
            owner != null
        }
        return checkNotNull(owner)
    }

    /**
     * Creates ([down]) or removes the simulated payment gateway's outage file in every payment pod; the outage is a
     * fault of the report, so the errors it causes are allowed.
     */
    private fun outage(down: Boolean) {
        if (down) {
            synchronized(report) { report.faults += Fault("payment gateway outage", Instant.now()) }
        } else {
            synchronized(report) { report.faults.last { it.name == "payment gateway outage" }.end = Instant.now() }
        }
        val command = if (down) arrayOf("touch", OUTAGE_FILE) else arrayOf("rm", "-f", OUTAGE_FILE)
        kube.pods("eshop-payment").filter { !it.deleting }.forEach { pod ->
            kube.exec(
                pod.name,
                *command
            ).let { check(it.ok) { "${command.joinToString(" ")} in ${pod.name}: ${it.err}" } }
        }
    }

    /** The ids of the entries ops lists as exhausted that failed since [since], as an operator queries them. */
    private fun exhausted(since: Long): List<String> {
        val filter = """{"op":"AND","operands":[{"op":"EQ","field":"state.context","value":"eshop-payment"},""" +
            """{"op":"EQ","field":"state.status","value":"EXHAUSTED"},{"op":"GTE","field":"state.failedAt","value":$since}]}"""
        val answer = shop.post("/ops/parked/snapshot/query", """{"kind":"list","filter":$filter}""", Who.OPERATOR)
        check(
            answer.status == ShopFlows.OK
        ) { "the ops query answered ${answer.status}: ${answer.body.take(ShopFlows.BODY)}" }
        return json.readTree(answer.body).values().map { it["aggregateId"].asString() }
    }

    private fun opsEntry(id: String): tools.jackson.databind.JsonNode? {
        val answer = shop.get("/ops/parked/$id/state", Who.OPERATOR)
        if (answer.status == ShopFlows.NOT_FOUND) return null
        check(
            answer.status == ShopFlows.OK
        ) { "GET the ops view of $id answered ${answer.status}: ${answer.body.take(ShopFlows.BODY)}" }
        return json.readTree(answer.body)["state"]
    }

    /**
     * One HTTP/1.1 request from [pod] to [host]:[port], written with bash's /dev/tcp (the probe pods have no HTTP
     * client): the status code, then the response; `000` when no connection was made within 5 seconds.
     */
    private fun http(
        pod: String,
        host: String,
        port: Int,
        method: String,
        path: String,
        body: String = "",
        vararg headers: Pair<String, String>,
    ): String {
        val request = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n$body")
        }
        val script = "exec 3<>/dev/tcp/$host/$port && printf '%s' \"\$REQUEST\" >&3 && cat <&3"
        val result = kube.exec(pod, "env", "REQUEST=$request", "timeout", "5", "bash", "-c", script)
        val status = result.out.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)
        return if (result.ok && status != null) "$status ${result.out}" else "000 ${result.err.trim()}"
    }

    /** Whether [pod] gets any response from [host]:[port] within 5 seconds: its status, `000` when none. */
    private fun reach(pod: String, host: String, port: Int): String = http(pod, host, port, "GET", "/wow/manifest")

    /** Full-chain checkouts on a thread of their own while a fault runs, so it happens mid-checkout. */
    private class Load(private val flows: ShopFlows) {
        @Volatile
        private var running = false
        private var worker: Thread? = null
        private val count = AtomicInteger()
        val sent: Int get() = count.get()

        fun start(): Load = apply {
            running = true
            worker = thread(name = "load", isDaemon = true) {
                while (running) {
                    val buyer = "load-${LOADS.incrementAndGet()}"
                    runCatching {
                        if (flows.addItem(buyer, "sock", "5.00", 1).status == ShopFlows.OK) {
                            flows.checkout(buyer, mapOf(ShopFlows.WAIT to ShopFlows.FULL_CHAIN, ShopFlows.WAIT_TIMEOUT to "20s"))
                            count.incrementAndGet()
                        }
                    }
                    Thread.sleep(LOAD_PAUSE.toMillis())
                }
            }
        }

        fun stop() {
            running = false
            worker?.join(Outages.JOIN.toMillis())
        }

        private companion object {
            val LOADS = AtomicInteger()
            val LOAD_PAUSE: Duration = Duration.ofMillis(200)
        }
    }

    private companion object {
        const val SECRET = "ga-e2e-not-a-secret-only-for-the-kind-cluster-0123456789"
        const val FORBIDDEN = 403
        const val UNAUTHORIZED = 401
        const val MILLIS = 1000
        const val LISTED = 10
        const val PARTITIONS = 64
        const val GATEWAY_SAGA = "PaymentGatewaySaga.onPaymentRequested"
        const val OUTAGE_FILE = "/tmp/gateway.down"
        const val ORDERING_GROUP = "eshop-ordering-service/eshop-ordering"
        val ORDER = EshopOrderingContract.Order.TYPE
        val PRODUCTS = mapOf(
            "mug" to "8.50",
            "cap" to "12.00",
            "tv" to "1500.00",
            "pen" to "2.00",
            "pad" to "3.00",
            "kite" to "20.00",
            "sock" to "5.00",
        )
        val STOCK = mapOf("mug" to 50, "cap" to 5, "tv" to 2, "pen" to 1, "pad" to 10, "kite" to 10, "sock" to 100_000)

        /** The public and internal ports, by Service name. */
        val PUBLIC = listOf(
            "eshop-catalog",
            "eshop-basket",
            "eshop-ordering",
            "eshop-inventory",
            "eshop-payment",
            "ops",
            "view-store",
            "analytics-catalog",
        ).map { it to 80 }
        val INTERNAL = listOf(
            "eshop-catalog" to 9091,
            "eshop-basket" to 9092,
            "eshop-ordering" to 9093,
            "eshop-inventory" to 9094,
            "eshop-payment" to 9095,
            "ops" to 9098,
            "view-store" to 9090,
            "analytics-catalog" to 9097,
        )

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
