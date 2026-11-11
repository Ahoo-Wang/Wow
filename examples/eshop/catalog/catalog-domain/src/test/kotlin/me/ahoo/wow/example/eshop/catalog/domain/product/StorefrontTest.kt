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

package me.ahoo.wow.example.eshop.catalog.domain.product

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.example.eshop.catalog.api.product.ProductCreated
import me.ahoo.wow.example.eshop.catalog.api.product.ProductDelisted
import me.ahoo.wow.example.eshop.catalog.api.product.ProductListing
import me.ahoo.wow.example.eshop.catalog.api.product.ProductPublished
import me.ahoo.wow.example.eshop.catalog.api.product.Quote
import me.ahoo.wow.example.eshop.catalog.api.product.QuoteLine
import me.ahoo.wow.example.eshop.catalog.api.product.QuoteRequest
import me.ahoo.wow.example.eshop.catalog.api.product.QuotedLine
import me.ahoo.wow.test.query.memoryQueries
import me.ahoo.wow.test.routeCall
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/** The storefront's routes, called as a handler is: with a call the test DSL builds, over a query side in memory. */
class StorefrontTest {
    private val model = productModel()

    private fun created(price: String, categoryId: String? = "mugs") =
        ProductCreated("Mug $price", BigDecimal(price), "A mug", brandId = "wow", categoryId = categoryId)

    // region call
    /** A shopper's call: its reads run on a query side in memory as the anonymous caller; it sends no command. */
    private val call = routeCall(
        queries = memoryQueries {
            snapshot(model, "mug", created("8.50"), ProductPublished)
            snapshot(model, "cup", created("5.00"), ProductPublished)
            snapshot(model, "bowl", created("7.00"), ProductPublished)
            snapshot(model, "plate", created("3.00", categoryId = "plates"), ProductPublished)
            snapshot(model, "draft", created("1.00"))
            snapshot(model, "old", created("2.00"), ProductPublished, ProductDelisted("Out of season"))
        }.of(Caller.ANONYMOUS),
    )
    // endregion

    private val storefront = Storefront()

    private fun <T> route(handle: suspend Storefront.() -> T): T = runBlocking { storefront.handle() }

    private fun refused(handle: suspend Storefront.() -> Unit): RouteRefusal =
        assertThrows<RouteRefusal> { route(handle) }

    @Test
    fun `a published product should be listed, any other product answer none`() {
        route { listing(call, "mug") }.assert().isEqualTo(
            ProductListing("mug", "Mug 8.50", "A mug", BigDecimal("8.50"), "wow", "mugs"),
        )
        route { listing(call, "draft") }.assert().isNull()
        route { listing(call, "old") }.assert().isNull()
        route { listing(call, "none") }.assert().isNull()
    }

    @Test
    fun `a category's listings should be its published products, cheapest first, as many as asked`() {
        route { categoryListings(call, "mugs", null) }.map { it.id }.assert().containsExactly("cup", "bowl", "mug")
        route { categoryListings(call, "mugs", 2) }.map { it.id }.assert().containsExactly("cup", "bowl")
        route { categoryListings(call, "plates", Storefront.MAX_LIMIT) }.map { it.id }.assert().containsExactly("plate")
    }

    @Test
    fun `a page of listings should hold 1 to 50 products`() {
        listOf(0, Storefront.MAX_LIMIT + 1).forEach { limit ->
            refused { categoryListings(call, "mugs", limit) }.code.assert()
                .isEqualTo(Storefront.Refusals.LIMIT_OUT_OF_RANGE)
        }
    }

    @Test
    fun `a quote should price each line at its product's price`() {
        route { quote(call, QuoteRequest(listOf(QuoteLine("mug", 2), QuoteLine("cup", 1)))) }.assert().isEqualTo(
            Quote(
                listOf(
                    QuotedLine("mug", 2, BigDecimal("8.50"), BigDecimal("17.00")),
                    QuotedLine("cup", 1, BigDecimal("5.00"), BigDecimal("5.00")),
                ),
                BigDecimal("22.00"),
            ),
        )
        route { quote(call, QuoteRequest(emptyList())) }.total.assert().isEqualByComparingTo("0")
    }

    @Test
    fun `a quote should be refused a product off the storefront or a quantity under one`() {
        listOf("draft", "old", "none").forEach { id ->
            refused { quote(call, QuoteRequest(listOf(QuoteLine(id, 1)))) }.code.assert()
                .isEqualTo(Storefront.Refusals.NOT_ON_SALE)
        }
        refused { quote(call, QuoteRequest(listOf(QuoteLine("mug", 0)))) }.code.assert()
            .isEqualTo(Storefront.Refusals.QUANTITY_NOT_POSITIVE)
    }
}
