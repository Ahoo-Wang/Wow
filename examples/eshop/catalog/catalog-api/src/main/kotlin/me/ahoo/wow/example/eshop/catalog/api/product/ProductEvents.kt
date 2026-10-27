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

import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

/** The skeleton's event (E.0) with the fields E.1 adds; optional fields with defaults are not a new revision. */
@Summary("A product was added to the catalog")
data class ProductCreated(
    val name: String,
    val price: BigDecimal,
    val description: String = "",
    val brandId: String? = null,
    val categoryId: String? = null,
)

@Summary("A product's name, description, brand or category changed")
data class ProductUpdated(
    val name: String,
    val description: String,
    val brandId: String?,
    val categoryId: String?,
)

@Summary("A product's price changed")
data class ProductPriceChanged(val price: BigDecimal, val previous: BigDecimal)

@Summary("A product was published on the storefront")
data object ProductPublished

@Summary("A product was taken off the storefront")
data class ProductDelisted(val reason: String)

@Summary("A product was discontinued for good")
data class ProductDiscontinued(val reason: String)
