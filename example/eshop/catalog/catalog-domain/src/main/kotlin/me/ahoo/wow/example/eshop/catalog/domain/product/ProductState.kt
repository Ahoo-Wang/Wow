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

import me.ahoo.wow.api.query.annotation.QueryIndex
import me.ahoo.wow.example.eshop.catalog.api.product.ProductCreated
import me.ahoo.wow.example.eshop.catalog.api.product.ProductDelisted
import me.ahoo.wow.example.eshop.catalog.api.product.ProductDiscontinued
import me.ahoo.wow.example.eshop.catalog.api.product.ProductPriceChanged
import me.ahoo.wow.example.eshop.catalog.api.product.ProductPublished
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStatus
import me.ahoo.wow.example.eshop.catalog.api.product.ProductUpdated
import java.math.BigDecimal

/**
 * The state of a [Product], changed in place by its events. It is the storefront's read model too: the snapshot
 * queries filter it by category, brand and status and sort it by price, so each of those leads an index whose further
 * keys are the storefront's usual filter and sort ("the published products of a category, cheapest first").
 */
class ProductState {
    var name: String = ""
        private set

    var description: String = ""
        private set

    // region indexes
    @field:QueryIndex
    var price: BigDecimal = BigDecimal.ZERO
        private set

    @field:QueryIndex(with = ["state.status", "state.price"])
    var brandId: String? = null
        private set

    @field:QueryIndex(with = ["state.status", "state.price"])
    var categoryId: String? = null
        private set

    @field:QueryIndex(with = ["state.price"])
    var status: ProductStatus = ProductStatus.DRAFT
        private set
    // endregion

    fun onSourcing(event: ProductCreated) {
        name = event.name
        price = event.price
        description = event.description
        brandId = event.brandId
        categoryId = event.categoryId
    }

    fun onSourcing(event: ProductUpdated) {
        name = event.name
        description = event.description
        brandId = event.brandId
        categoryId = event.categoryId
    }

    fun onSourcing(event: ProductPriceChanged) {
        price = event.price
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: ProductPublished) {
        status = ProductStatus.PUBLISHED
    }

    @Suppress("UnusedParameter")
    fun onSourcing(event: ProductDelisted) {
        status = ProductStatus.DELISTED
    }

    /** A discontinued product is off the storefront; the kernel marks it deleted by the lifecycle event beside. */
    @Suppress("UnusedParameter")
    fun onSourcing(event: ProductDiscontinued) {
        status = ProductStatus.DELISTED
    }
}
