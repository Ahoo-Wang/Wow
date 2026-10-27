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

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Summary
import java.math.BigDecimal

/**
 * Adds a draft product named [name] at [price] to the catalog. The [brandId] and [categoryId] name a brand and a
 * category of this context; a product can be created without them, but is published only with both.
 */
@CreateAggregate
@Summary("Create a product")
data class CreateProduct(
    @field:NotBlank val name: String,
    @field:Positive val price: BigDecimal,
    val description: String = "",
    val brandId: String? = null,
    val categoryId: String? = null,
)

/** Replaces the product's name, description, brand and category. */
@Summary("Update a product")
data class UpdateProduct(
    @field:NotBlank val name: String,
    val description: String = "",
    val brandId: String? = null,
    val categoryId: String? = null,
)

/** Changes the product's price. */
@Summary("Change a product's price")
data class ChangeProductPrice(@field:Positive val price: BigDecimal)

/** Puts the product on the storefront; a delisted product can be published again. */
@Summary("Publish a product")
data object PublishProduct

/** Takes a published product off the storefront. */
@Summary("Delist a product")
data class DelistProduct(val reason: String = "")

/**
 * Discontinues the product for good, with the reason: it leaves the catalog, deleted as `DeleteAggregate` deletes it,
 * the reason kept in its history.
 */
@Summary("Discontinue a product")
data class DiscontinueProduct(val reason: String)
