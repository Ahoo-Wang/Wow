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

import me.ahoo.wow.example.eshop.e2e.Answer
import me.ahoo.wow.example.eshop.e2e.EshopStack
import me.ahoo.wow.example.eshop.e2e.json
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The eShop's flows as a client drives them through the gateway (the requests of EshopFullChainTest), and what the
 * run did, for the verdict: the stock each product received, every buyer's checkouts (its orders are `<buyer>-<n>`)
 * and the orders shipped.
 */
class ShopFlows(val shop: Shop) {
    /** Each product's received quantity. */
    val received: MutableMap<String, Int> = ConcurrentHashMap()

    /** Each buyer's checkout attempts: its orders are `<buyer>-1` up to that many. */
    val checkouts: MutableMap<String, AtomicInteger> = ConcurrentHashMap()

    /** The orders the run shipped. */
    val shipped: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun product(id: String, name: String, price: String) {
        // The brand and the category exist after the first product: their second creation is refused, and ignored.
        shop.post("/eshop-catalog/brand/wow/create-brand", """{"name":"Wow"}""", Who.EMPLOYEE)
        shop.post("/eshop-catalog/category/goods/create-category", """{"name":"Goods"}""", Who.EMPLOYEE)
        shop.post(
            "/eshop-catalog/product/$id/create-product",
            """{"name":"$name","price":$price,"brandId":"wow","categoryId":"goods"}""",
            Who.EMPLOYEE,
        ).require("create the product $id")
        shop.post("/eshop-catalog/product/$id/publish-product", "{}", Who.EMPLOYEE).require("publish $id")
    }

    /** The price of [product], as a buyer reads it from the catalog. */
    fun price(product: String): String {
        val state = checkNotNull(
            state("/eshop-catalog/product/$product/state", Who.buyer("dave"))
        ) { "No product $product." }
        check(state["status"].asString() == "PUBLISHED") { "$product is ${state["status"]}." }
        return state["price"].decimalValue().toPlainString()
    }

    fun receive(product: String, quantity: Int) {
        shop.post("/eshop-inventory/stock/$product/receive-stock", """{"quantity":$quantity}""", Who.EMPLOYEE)
            .require("receive $quantity of $product")
        received.merge(product, quantity, Int::plus)
    }

    fun addItem(buyer: String, product: String, price: String, quantity: Int): Answer = shop.post(
        "/eshop-basket/basket/$buyer/add-basket-item",
        """{"productId":"$product","unitPrice":$price,"quantity":$quantity}""",
        Who.buyer(buyer),
    )

    /** Checks [buyer]'s basket out with [headers] (a Wow-Wait); the answer, whatever it is. */
    fun checkout(buyer: String, headers: Map<String, String> = emptyMap(), timeout: Duration = Shop.TIMEOUT): Answer {
        checkouts.computeIfAbsent(buyer) { AtomicInteger() }.incrementAndGet()
        return shop.post("/eshop-basket/basket/$buyer/checkout-basket", "{}", Who.buyer(buyer), headers, timeout)
    }

    /** Checks out with the wait [chain] and its [waitTimeout]: the final result, failing unless it was answered 200. */
    fun checkoutWaiting(buyer: String, chain: String, waitTimeout: String = "30s"): JsonNode {
        val answer = checkout(
            buyer,
            mapOf(WAIT to chain, WAIT_TIMEOUT to waitTimeout)
        ).require("check $buyer's basket out")
        return json.readTree(answer.body)
    }

    fun ship(order: String) {
        shop.post("/eshop-ordering/order/$order/ship-order", "{}", Who.EMPLOYEE).require("ship $order")
        shipped += order
    }

    /** The state at [path], read by [who] (an employee by default), or null while there is none. */
    fun state(path: String, who: Who = Who.EMPLOYEE): JsonNode? = view(path, who)?.get("state")

    /** The full snapshot view at [path] (state, scope, version), or null while there is none. */
    fun view(path: String, who: Who = Who.EMPLOYEE): JsonNode? {
        val answer = shop.get(path, who)
        if (answer.status == NOT_FOUND) return null
        check(answer.status == OK) { "GET $path answered ${answer.status}: ${answer.body.take(BODY)}" }
        return json.readTree(answer.body)
    }

    fun order(id: String): JsonNode? = state("/eshop-ordering/order/$id/state")

    fun orderStatus(id: String): String? = order(id)?.get("status")?.asString()

    fun payment(id: String): JsonNode? = state("/eshop-payment/payment/$id/state")

    fun stock(product: String): JsonNode =
        checkNotNull(state("/eshop-inventory/stock/$product/state")) { "No stock of $product." }

    /** The commits of the aggregate at [path] (`…/state/trace`): each with its version, request id and event names. */
    fun trace(path: String): List<TracedCommit> {
        val commits = mutableListOf<TracedCommit>()
        var from = 1
        while (true) {
            val answer = shop.get("$path/state/trace?from=$from&limit=$TRACE_PAGE", Who.EMPLOYEE)
            if (answer.status == NOT_FOUND) return commits
            check(answer.status == OK) { "GET $path/state/trace answered ${answer.status}: ${answer.body.take(BODY)}" }
            val page = json.readTree(answer.body)["commits"].values().map { commit ->
                TracedCommit(
                    commit["version"].asInt(),
                    commit["requestId"].asString(),
                    commit["events"].values().map { it["name"].asString().substringAfterLast('.') },
                )
            }
            commits += page
            if (page.size < TRACE_PAGE) return commits
            from = page.last().version + 1
        }
    }

    /** Every order the run's checkouts may have made: `<buyer>-<n>` for each attempt. */
    fun candidateOrders(): List<String> = checkouts.flatMap { (buyer, count) -> (1..count.get()).map { "$buyer-$it" } }

    /** [condition], polled until it holds, for at most [deadline]; the failure says [what] and the [detail]. */
    fun eventually(
        what: String,
        deadline: Duration = DEADLINE,
        detail: () -> String = { "" },
        condition: () -> Boolean
    ) {
        val until = System.nanoTime() + deadline.toNanos()
        while (!runCatching(condition).getOrDefault(false)) {
            if (System.nanoTime() > until) throw AssertionError("Not reached in $deadline: $what. ${detail()}")
            Thread.sleep(POLL.toMillis())
        }
    }

    data class TracedCommit(val version: Int, val requestId: String, val events: List<String>)

    companion object {
        const val OK = 200
        const val NOT_FOUND = 404
        const val BODY = 600
        const val TRACE_PAGE = 1000
        const val WAIT = "Wow-Wait"
        const val WAIT_TIMEOUT = "Wow-Wait-Timeout"
        const val TO_ORDERING = EshopStack.CHECKOUT_TO_ORDER
        const val FULL_CHAIN = EshopStack.CHECKOUT_TO_PAID
        val DEADLINE: Duration = Duration.ofSeconds(90)
        val POLL: Duration = Duration.ofMillis(500)

        /** The sum of an order's items, unit price times quantity. */
        fun total(order: JsonNode): BigDecimal = order["items"].values().fold(BigDecimal.ZERO) { sum, item ->
            sum + item["unitPrice"].decimalValue() * item["quantity"].decimalValue()
        }

        fun Answer.require(what: String): Answer {
            check(status == OK) { "$what: answered $status ${body.take(BODY)}" }
            return this
        }
    }
}
