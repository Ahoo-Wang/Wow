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

package me.ahoo.wow.example.eshop.catalog.api.product

import java.math.BigDecimal

/** A published product as the storefront shows it to a shopper: what it is and what it costs. */
data class ProductListing(
    val id: String,
    val name: String,
    val description: String,
    val price: BigDecimal,
    val brandId: String?,
    val categoryId: String?,
)

/** The lines a shopper asks the storefront to price. */
data class QuoteRequest(val lines: List<QuoteLine>)

/** [quantity] of the product [productId]. */
data class QuoteLine(val productId: String, val quantity: Int)

/** The storefront's price of a [QuoteRequest]: each line at its product's price, and their [total]. */
data class Quote(val lines: List<QuotedLine>, val total: BigDecimal)

/** A line of a [Quote]: [quantity] of [productId] at [unitPrice], [amount] in all. */
data class QuotedLine(val productId: String, val quantity: Int, val unitPrice: BigDecimal, val amount: BigDecimal)
