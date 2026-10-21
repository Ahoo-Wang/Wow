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

package me.ahoo.wow.rehearsal.driver

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The answer of a command: whether it was applied, the aggregate's version after it, why not, and the aggregate's id
 * when the version chose it (a 9.x view's create).
 */
data class Outcome(val ok: Boolean, val version: Int?, val error: String?, val id: String? = null)

/**
 * One version's routes for the example's order and cart: the 9.x example-server's (wow-webflux, the paths its OpenAPI
 * lists) or the rehearsal host's (wow-http). Both answer a command once its events are stored.
 */
sealed class Endpoints(val base: URI) {
    protected val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    abstract fun createOrder(order: OrderRef, body: String): Outcome

    abstract fun changeAddress(order: OrderRef, body: String): Outcome

    abstract fun payOrder(order: OrderRef, body: String): Outcome

    abstract fun shipOrder(order: OrderRef): Outcome

    abstract fun receiptOrder(order: OrderRef): Outcome

    abstract fun addCartItem(customer: String, body: String): Outcome

    /** The order's state and version; null when it does not exist. */
    abstract fun orderState(order: OrderRef): Pair<Int, JsonNode>?

    protected fun send(request: HttpRequest.Builder, body: String?, method: String): HttpResponse<String> = client.send(
        request.timeout(REQUEST_TIMEOUT)
            .header("Content-Type", JSON)
            .header("Accept", JSON)
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            )
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** The 9.x example-server: `/tenant/{tenant}/owner/{owner}/sales-order…`, `CommandResult` answers. */
    class NineX(base: URI) : Endpoints(base) {
        override fun createOrder(order: OrderRef, body: String): Outcome =
            command("/tenant/${order.tenant}/owner/${order.owner}/sales-order", body, "POST", order.id)

        override fun changeAddress(order: OrderRef, body: String): Outcome =
            command("/tenant/${order.tenant}/owner/${order.owner}/sales-order/${order.id}/address", body, "PUT")

        override fun payOrder(order: OrderRef, body: String): Outcome =
            command("/tenant/${order.tenant}/sales-order/${order.id}/pay", body, "POST")

        override fun shipOrder(order: OrderRef): Outcome =
            command("/tenant/${order.tenant}/sales-order/${order.id}/package", "{}", "POST")

        override fun receiptOrder(order: OrderRef): Outcome =
            command("/tenant/${order.tenant}/owner/${order.owner}/sales-order/${order.id}/package", "{}", "PATCH")

        override fun addCartItem(customer: String, body: String): Outcome =
            command("/owner/$customer/cart/add_cart_item", body, "POST")

        override fun orderState(order: OrderRef): Pair<Int, JsonNode>? {
            val response = send(
                HttpRequest.newBuilder(
                    base.resolve("/tenant/${order.tenant}/owner/${order.owner}/sales-order/${order.id}/state")
                ),
                null,
                "GET",
            )
            if (response.statusCode() != OK) {
                return null
            }
            val state = MAPPER.readTree(response.body())
            // 9.x's state read answers the state alone; its version is its last stream's, which the driver tracks.
            return -1 to state
        }

        private fun command(path: String, body: String, method: String, aggregateId: String? = null): Outcome {
            val request = HttpRequest.newBuilder(base.resolve(path))
                .header("Command-Wait-Stage", "PROCESSED")
                .header("Command-Wait-Timeout", WAIT_MILLIS.toString())
            aggregateId?.let { request.header("Command-Aggregate-Id", it) }
            val response = send(request, body, method)
            val node = runCatching { MAPPER.readTree(response.body()) }.getOrNull()
            val code = node?.get("errorCode")?.asString()
            return if (response.statusCode() == OK && code == "Ok") {
                Outcome(true, node.get("aggregateVersion")?.asInt(), null)
            } else {
                Outcome(
                    false,
                    null,
                    "http-${response.statusCode()} ${code ?: ""} ${node?.get("errorMsg")?.asString() ?: ""}"
                )
            }
        }
    }

    /** The rehearsal host: `/example/order/{id}/<command>`, the driver's tenant and owner in its headers. */
    class V10(base: URI) : Endpoints(base) {
        override fun createOrder(order: OrderRef, body: String): Outcome = command(
            order,
            "create-order",
            body,
            owner = order.owner
        )

        override fun changeAddress(order: OrderRef, body: String): Outcome = command(order, "change-address", body)

        override fun payOrder(order: OrderRef, body: String): Outcome = command(order, "pay-order", body)

        override fun shipOrder(order: OrderRef): Outcome = command(order, "ship-order", "{}")

        override fun receiptOrder(order: OrderRef): Outcome = command(order, "receipt-order", "{}")

        override fun addCartItem(customer: String, body: String): Outcome {
            val request = HttpRequest.newBuilder(base.resolve("/example/cart/$customer/add-cart-item"))
                .header("Wow-Wait", "committed")
                .header(OWNER, customer)
            return outcome(send(request, body, "POST"))
        }

        override fun orderState(order: OrderRef): Pair<Int, JsonNode>? {
            val response = send(
                HttpRequest.newBuilder(base.resolve("/example/order/${order.id}/state")).header(TENANT, order.tenant),
                null,
                "GET",
            )
            if (response.statusCode() != OK) {
                return null
            }
            val node = MAPPER.readTree(response.body())
            return node.get("version").asInt() to node.get("state")
        }

        private fun command(order: OrderRef, name: String, body: String, owner: String? = null): Outcome {
            val request = HttpRequest.newBuilder(base.resolve("/example/order/${order.id}/$name"))
                .header("Wow-Wait", "committed")
                .header(TENANT, order.tenant)
            owner?.let { request.header(OWNER, it) }
            return outcome(send(request, body, "POST"))
        }

        private fun outcome(response: HttpResponse<String>): Outcome {
            val node = runCatching { MAPPER.readTree(response.body()) }.getOrNull()
            val result = node?.get("result") ?: node
            return if (response.statusCode() == OK && result?.get("status")?.asString() == "SUCCEEDED") {
                Outcome(true, result.get("version")?.asInt(), null)
            } else {
                Outcome(false, null, "http-${response.statusCode()} ${node?.get("code")?.asString() ?: ""}")
            }
        }

        private companion object {
            const val TENANT = "X-Rehearsal-Tenant"
            const val OWNER = "X-Rehearsal-Owner"
        }
    }

    companion object {
        const val OK = 200
        const val WAIT_MILLIS = 10_000
        private const val JSON = "application/json"
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        val MAPPER: JsonMapper = JsonMapper.builder().build()

        fun of(version: String, base: String): Endpoints = when (version) {
            "9x" -> NineX(URI.create(base))
            "v10" -> V10(URI.create(base))
            else -> throw IllegalArgumentException("Unknown version '$version': 9x or v10.")
        }
    }
}
