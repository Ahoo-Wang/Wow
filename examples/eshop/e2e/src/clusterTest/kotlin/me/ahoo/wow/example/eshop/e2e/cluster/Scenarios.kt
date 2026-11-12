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
import java.time.Duration

/**
 * The scenarios the GA end-to-end run ([GaE2eTest]) and the rc drill ([RcDrillTest]) both run, each returning what
 * its report says, failing on what it checks: the catalog filed, the three flows a buyer meets, the boundary's two
 * ports, the partition owner deleted mid-checkout and a parked payment retried from ops.
 */
class Scenarios(
    private val flows: ShopFlows,
    private val kube: Kube,
    private val report: ClusterReport,
    private val network: PodNetwork,
    private val probes: ClusterProbes,
    private val ops: OpsDesk,
    private val reads: StoreReads,
) {
    fun catalogAndStock(): String {
        Deployment.PRODUCTS.forEach { (id, price) -> flows.product(id, "Wow $id", price) }
        Deployment.STOCK.forEach { (id, quantity) -> flows.receive(id, quantity) }
        return "${Deployment.PRODUCTS.size} products published, ${Deployment.STOCK.size} stocks received"
    }

    /** [buyer]'s checkout through the 15 hops to a paid order, then shipped: the order's number and the detail. */
    fun fullChain(buyer: String): Pair<String, String> {
        flows.addItem(buyer, "mug", flows.price("mug"), 2)
        flows.addItem(buyer, "cap", flows.price("cap"), 1)
        val result = flows.checkoutWaiting(buyer, ShopFlows.FULL_CHAIN)
        result["status"].asString().assert().describedAs(result.toString()).isEqualTo("succeeded")
        result["hop"]["stage"].asString().assert().isEqualTo("committed")
        result["hop"]["context"].asString().assert().isEqualTo("eshop-ordering")
        result["function"]["name"].asString().assert().isEqualTo("PayOrder")
        val number = flows.numberOf(result)
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
        return number to "$number paid in one Wow-Wait of 15 hops and shipped: ${events.joinToString(", ")}"
    }

    fun declinedPayment(buyer: String): String {
        flows.addItem(buyer, "tv", flows.price("tv"), 1)
        val ended = flows.checkoutWaiting(buyer, ShopFlows.FULL_CHAIN)
        ended["status"].asString().assert().describedAs(ended.toString()).isEqualTo("succeeded")
        ended["hop"]["subscription"].asString().assert().isEqualTo("OrderPaymentSaga.onPaymentSucceeded")
        ended["chainEnded"].asBoolean().assert().isTrue()
        // The payment saga's hop names the payment, whose id is its order's number.
        val number = flows.numberOf(ended)
        flows.eventually("$number cancelled") { flows.orderStatus(number) == "CANCELLED" }
        checkNotNull(flows.payment(number))["status"].asString().assert().isEqualTo("FAILED")
        return "$number declined over the gateway's limit: the wait ended at the payment saga, the order cancelled"
    }

    fun stockRejection(buyer: String): String {
        flows.addItem(buyer, "pad", flows.price("pad"), 1)
        flows.addItem(buyer, "pen", flows.price("pen"), 3)
        val number = flows.numberOf(flows.checkoutWaiting(buyer, ShopFlows.TO_ORDERING))
        flows.eventually("$number cancelled") { flows.orderStatus(number) == "CANCELLED" }
        checkNotNull(flows.order(number))["cancelReason"].asString().assert().contains("pen")
        // The compensation: the pad reserved for $number is released.
        flows.eventually("the pad released") {
            flows.stock("pad")["available"].asInt() == Deployment.STOCK.getValue("pad") && flows.stock("pad")["reservations"][number] == null
        }
        flows.stock("pen")["available"].asInt().assert().isEqualTo(Deployment.STOCK.getValue("pen"))
        flows.payment(number).assert().isNull()
        return "$number cancelled for the pen, the pad's reservation released, no payment requested"
    }

    fun publicPorts(excluded: Collection<String>): String {
        val blocked = Deployment.PUBLIC.filter { it.first !in excluded }.map { (service, port) ->
            val outsider = network.reach("outsider", service, port)
            val insider = network.reach("insider", service, port)
            check(outsider.startsWith("000")) { "the outsider reached $service's public port: $outsider" }
            check(
                insider.startsWith("000")
            ) { "a shop pod that is not the gateway reached $service's public port: $insider" }
            service
        }
        return "blocked around the gateway: ${blocked.joinToString()}"
    }

    fun internalPorts(excluded: Collection<String>): String {
        Deployment.INTERNAL.filter { it.first !in excluded }.forEach { (service, port) ->
            val outsider = network.reach("outsider", "$service-cluster", port)
            check(outsider.startsWith("000")) { "the outsider reached $service's internal port: $outsider" }
        }
        // The positive control: a pod of the shop reaches them, so the refusals are the policies'.
        Deployment.INTERNAL.filter { it.first.startsWith("eshop-") && it.first !in excluded }.forEach { (service, port) ->
            val insider = network.reach("insider", "$service-cluster", port)
            check(!insider.startsWith("000")) { "the shop's own pod did not reach $service's internal port: $insider" }
        }
        return "outsider refused on ${Deployment.INTERNAL.size} internal ports; the shop's pod served on the eShop's"
    }

    /**
     * Force-deletes the owner of [buyer]'s order's partition mid-checkout; the order is paid once and shipped. The
     * ordering numbers the order, so its partition, and the pod that owns it, are known once the checkout answers at
     * the order's commit: the pod is deleted then, while the order process goes on (the reservation, the payment).
     */
    fun deleteOwnerMidCheckout(buyer: String): String {
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

    /** [buyer]'s payment parked by the payment gateway's outage, seen in ops, retried by an operator, then paid. */
    fun parkedRetriedFromOps(buyer: String): String {
        val started = System.currentTimeMillis()
        ops.outage(true)
        flows.addItem(buyer, "mug", flows.price("mug"), 1)
        val number = flows.numberOf(flows.checkoutWaiting(buyer, ShopFlows.TO_ORDERING))
        val id = parkedPayment(number)
        flows.eventually("the parked payment listed by ops", OPS_BOUND) { ops.exhausted(started).contains(id) }
        val received = System.currentTimeMillis() - started
        flows.orderStatus(number).assert().isEqualTo("STOCK_CONFIRMED")
        flows.shop.post("/ops/parked/$id/retry", "{}", Who.VIEWER).status.assert().isEqualTo(FORBIDDEN)
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
        return "parked, seen in ops after $received ms, retried by an operator, resolved and paid; the ledger entry is gone"
    }

    /** The failure ledger's id of the payment of the order [number], parked by the payment gateway's saga. */
    fun parkedPayment(number: String): String = Ledger.parkedId(
        "eshop-payment-service",
        "eshop-payment",
        GATEWAY_SAGA,
        EshopPaymentContract.Payment.ref(Caller.DEFAULT_TENANT, number)
    )

    companion object {
        const val GATEWAY_SAGA = "PaymentGatewaySaga.onPaymentRequested"
        const val FORBIDDEN = 403
        val OPS_BOUND: Duration = Duration.ofSeconds(90)
        val TAKEOVER_BOUND: Duration = Duration.ofSeconds(120)
    }
}
