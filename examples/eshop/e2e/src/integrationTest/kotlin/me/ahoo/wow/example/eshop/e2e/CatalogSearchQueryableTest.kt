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
 * The catalog with its `search` profile on real stores: the products' log and snapshots on MongoDB, a copy of their
 * snapshots on Elasticsearch, which their snapshot queries read, its writes searchable when they return
 * (`refresh: wait-for`). A command sent over HTTP with `Wow-Wait: queryable` answers once the copy holds it, so the
 * product list read next has the new state, every time. The same request with `snapshot` ends at MongoDB and is not
 * held to that.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogSearchQueryableTest {
    private val run = UUID.randomUUID().toString().take(8)
    private val server = wowServer<CatalogServer>(
        "search",
        "dev",
        properties = mapOf(
            "spring.application.name" to "eshop-catalog-service",
            "spring.mongodb.uri" to WowContainers.mongoUrl,
            "spring.elasticsearch.uris" to "http://" + WowContainers.elasticsearch.httpHostAddress,
            "wow.stores.main.database" to "e2e_queryable_$run",
            "wow.stores.search.index-prefix" to "e2e-queryable-$run",
            "wow.stores.search.refresh" to "wait-for",
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
    fun `a price changed with the queryable wait should be in the product list read next, every time`() {
        val id = post(
            "/eshop-catalog/product/create-product",
            """{"name":"Queryable mug $run","price":1.00}""",
            QUERYABLE,
        ).assertOk().body!!["ref"]["id"].asString()
        listed(id).assert().isEqualByComparingTo("1.00")

        repeat(REPETITIONS) { round ->
            val price = BigDecimal(round + 2).setScale(2)
            val changed = post("/eshop-catalog/product/$id/change-product-price", """{"price":$price}""", QUERYABLE)
                .assertOk().body!!
            changed["hop"]["subscription"].asString().assert().isEqualTo("wow.queryable")
            // Read at once, with no retry: the list the copy serves already holds the change.
            listed(id).assert().describedAs("round $round").isEqualByComparingTo(price)
        }
    }

    /** The price the product list, served by the Elasticsearch copy, holds for the product [id]. */
    private fun listed(id: String): BigDecimal {
        val list = post(PRODUCTS, """{"kind":"list","filter":{"op":"id","value":"$id"}}""", EMPLOYEE).assertOk().body!!
        return list.values().single()["state"]["price"].decimalValue()
    }

    private fun post(path: String, body: String, headers: Map<String, String>): Answer =
        server.http.post(path, body, headers)

    private fun Answer.assertOk(): Answer = also { status.assert().describedAs(text).isEqualTo(OK) }

    private companion object {
        const val OK = 200
        const val REPETITIONS = 30
        const val PRODUCTS = "/eshop-catalog/product/snapshot/query"
        val EMPLOYEE = mapOf(USER to "erin", ROLES to "employee")
        val QUERYABLE = EMPLOYEE + ("Wow-Wait" to "queryable")
    }
}
