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

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.RouteMethod
import me.ahoo.wow.api.annotation.RouteQuery
import me.ahoo.wow.api.annotation.ServiceRoute
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.example.eshop.catalog.api.product.ProductListing
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStatus
import me.ahoo.wow.example.eshop.catalog.api.product.Quote
import me.ahoo.wow.example.eshop.catalog.api.product.QuoteRequest
import me.ahoo.wow.example.eshop.catalog.api.product.QuotedLine
import java.math.BigDecimal

/**
 * The storefront's reads of the catalog, service routes beside the product's queries: a product as a shopper sees it,
 * a category's products in a page, and the price of a basket's lines. Each reads as its caller ([RouteCall.queries]),
 * so it sees what the caller may; only a published product is on the storefront.
 */
class Storefront {
    // region listing
    /** The product [id] as the storefront shows it; none (`204 No Content`) while it is not published. */
    @ServiceRoute(method = RouteMethod.GET, path = "listings/{id}")
    suspend fun listing(call: RouteCall, id: String): ProductListing? {
        val product = call.queries.snapshots<ProductState>().state(id)?.state ?: return null
        return product.takeIf { it.status == ProductStatus.PUBLISHED }?.listing(id)
    }
    // endregion

    // region category
    /** The published products of [categoryId], cheapest first: [limit] of them, 20 when it is not given. */
    @ServiceRoute(
        method = RouteMethod.GET,
        path = "categories/{categoryId}/listings",
        errors = [Refusals.LIMIT_OUT_OF_RANGE],
    )
    suspend fun categoryListings(call: RouteCall, categoryId: String, @RouteQuery limit: Int?): List<ProductListing> {
        val size = limit ?: DEFAULT_LIMIT
        if (size !in 1..MAX_LIMIT) {
            throw RouteRefusal(Refusals.LIMIT_OUT_OF_RANGE, "A page holds 1 to $MAX_LIMIT products, not $size.")
        }
        val query = listQuery {
            filter {
                ProductStateProperties.STATE__CATEGORY_ID eq categoryId
                ProductStateProperties.STATE__STATUS eq ProductStatus.PUBLISHED
            }
            sort { ProductStateProperties.STATE__PRICE.asc() }
            limit(size)
        }
        return call.queries.snapshots<ProductState>().list(query).map { it.state.listing(it.ref.id) }.toList()
    }
    // endregion

    // region quote
    /** The price of [request]'s lines, each at its product's price; refused when one is not on the storefront. */
    @ServiceRoute(
        method = RouteMethod.POST,
        path = "quotes",
        errors = [Refusals.NOT_ON_SALE, Refusals.QUANTITY_NOT_POSITIVE],
    )
    suspend fun quote(call: RouteCall, request: QuoteRequest): Quote {
        val products = call.queries.snapshots<ProductState>()
        val lines = request.lines.map { line ->
            if (line.quantity < 1) {
                throw RouteRefusal(
                    Refusals.QUANTITY_NOT_POSITIVE,
                    "Product [${line.productId}] is quoted ${line.quantity}.",
                )
            }
            val product = products.state(line.productId)?.state?.takeIf { it.status == ProductStatus.PUBLISHED }
                ?: throw RouteRefusal(Refusals.NOT_ON_SALE, "Product [${line.productId}] is not on the storefront.")
            QuotedLine(line.productId, line.quantity, product.price, product.price * line.quantity.toBigDecimal())
        }
        return Quote(lines, lines.fold(BigDecimal.ZERO) { total, line -> total + line.amount })
    }
    // endregion

    private fun ProductState.listing(id: String) = ProductListing(id, name, description, price, brandId, categoryId)

    // region refusals
    /** The codes the storefront refuses a call with: full codes of the context, which its routes list. */
    object Refusals {
        @ErrorCode(description = "A page of listings holds 1 to 50 products.", status = 400)
        const val LIMIT_OUT_OF_RANGE = "eshop-catalog.limit-out-of-range"

        @ErrorCode(description = "A quote's product is not published on the storefront.", status = 409)
        const val NOT_ON_SALE = "eshop-catalog.not-on-sale"

        @ErrorCode(description = "A quote's line asks for one or more of its product.", status = 400)
        const val QUANTITY_NOT_POSITIVE = "eshop-catalog.quantity-not-positive"
    }
    // endregion

    companion object {
        /** The listings of a page when the call does not say. */
        const val DEFAULT_LIMIT = 20

        /** The most listings a page holds. */
        const val MAX_LIMIT = 50
    }
}
