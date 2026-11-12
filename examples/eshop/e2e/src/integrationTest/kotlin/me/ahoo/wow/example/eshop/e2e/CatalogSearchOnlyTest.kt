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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.DevelopmentHeaders.ROLES
import me.ahoo.wow.api.wire.DevelopmentHeaders.USER
import me.ahoo.wow.example.eshop.catalog.server.CatalogServer
import me.ahoo.wow.tck.containers.WowContainers
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID

/**
 * The catalog with its products' snapshots on Elasticsearch alone, its `search-only` profile: the products' log is on
 * MongoDB and their state is stored once, in the index their snapshot queries read, its writes searchable when they
 * return (`refresh: wait-for`). A command sent over HTTP with the default wait answers once the index holds it, so the
 * state read by id and the product list read next both have the new price, every time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogSearchOnlyTest {
    private val run = UUID.randomUUID().toString().take(8)
    private val server = wowServer<CatalogServer>(
        "search-only",
        "dev",
        properties = mapOf(
            "spring.application.name" to "eshop-catalog-service",
            "spring.mongodb.uri" to WowContainers.mongoUrl,
            "spring.elasticsearch.uris" to "http://" + WowContainers.elasticsearch.httpHostAddress,
            "wow.stores.main.database" to "e2e_search_only_$run",
            "wow.stores.search.index-prefix" to "e2e-search-only-$run",
            "wow.log.partitions" to 4,
            // The test's class path holds the other contexts' domains too.
            "wow.contexts.include" to "eshop-catalog",
            cosIdOf("catalog"),
        ),
    )

    @AfterAll
    fun close() {
        server.close()
    }

    @Test
    fun `a price changed with the default wait should be in the state and the product list read next, every time`() {
        val id = post("/eshop-catalog/product/create-product", """{"name":"Search-only mug $run","price":1.00}""")
            .assertOk().body!!["ref"]["id"].asString()
        state(id).assert().isEqualByComparingTo("1.00")
        listed(id).assert().isEqualByComparingTo("1.00")

        repeat(REPETITIONS) { round ->
            val price = BigDecimal(round + 2).setScale(2)
            val changed = post("/eshop-catalog/product/$id/change-product-price", """{"price":$price}""").assertOk()
                .body!!
            changed["hop"]["subscription"].asString().assert().isEqualTo("wow.snapshot")
            // Read at once, with no retry: the index the commands load from and the queries read holds the change.
            state(id).assert().describedAs("round $round, by id").isEqualByComparingTo(price)
            listed(id).assert().describedAs("round $round, listed").isEqualByComparingTo(price)
        }
    }

    /** The price the product [id]'s state, read by id from Elasticsearch, holds. */
    private fun state(id: String): BigDecimal {
        val state = server.http.get("/eshop-catalog/product/$id/state", EMPLOYEE).assertOk().body!!
        return state["state"]["price"].decimalValue()
    }

    /** The price the product list, served by the same index, holds for the product [id]. */
    private fun listed(id: String): BigDecimal {
        val list = post(PRODUCTS, """{"kind":"list","filter":{"op":"id","value":"$id"}}""").assertOk().body!!
        return list.values().single()["state"]["price"].decimalValue()
    }

    private fun post(path: String, body: String): Answer = server.http.post(path, body, EMPLOYEE)

    private fun Answer.assertOk(): Answer = also { status.assert().describedAs(text).isEqualTo(OK) }

    private companion object {
        const val OK = 200
        const val REPETITIONS = 30
        const val PRODUCTS = "/eshop-catalog/product/snapshot/query"
        val EMPLOYEE = mapOf(USER to "erin", ROLES to "employee")
    }
}
