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

import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.ErrorCode
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.example.eshop.catalog.api.product.ChangeProductPrice
import me.ahoo.wow.example.eshop.catalog.api.product.CreateProduct
import me.ahoo.wow.example.eshop.catalog.api.product.DelistProduct
import me.ahoo.wow.example.eshop.catalog.api.product.DiscontinueProduct
import me.ahoo.wow.example.eshop.catalog.api.product.ProductCreated
import me.ahoo.wow.example.eshop.catalog.api.product.ProductDelisted
import me.ahoo.wow.example.eshop.catalog.api.product.ProductDiscontinued
import me.ahoo.wow.example.eshop.catalog.api.product.ProductPriceChanged
import me.ahoo.wow.example.eshop.catalog.api.product.ProductPublished
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStatus
import me.ahoo.wow.example.eshop.catalog.api.product.ProductUpdated
import me.ahoo.wow.example.eshop.catalog.api.product.PublishProduct
import me.ahoo.wow.example.eshop.catalog.api.product.UpdateProduct

// region root
/**
 * A product of the catalog: a draft until it is published on the storefront, which takes a brand and a category; it
 * can be delisted and published again. Its name, description, brand, category and price change at any time.
 *
 * Every shopper of the tenant reads the products, and an employee changes them: a product belongs to its tenant
 * (`Ownership.TENANT`), so neither its reads nor its writes are restricted by an owner. The catalog
 * server's action-level authorization lets only an employee send its commands.
 */
@AggregateRoot(ownership = Ownership.TENANT)
@Description("A product of the catalog")
class Product(private val state: ProductState) {
// endregion
    @OnCommand(produces = [ProductCreated::class])
    fun onCommand(command: CreateProduct): Decision =
        Decision.events(
            ProductCreated(
                name = command.name,
                price = command.price,
                description = command.description,
                brandId = command.brandId,
                categoryId = command.categoryId,
            ),
        )

    @OnCommand(produces = [ProductUpdated::class])
    fun onCommand(command: UpdateProduct): Decision {
        if (state.status == ProductStatus.PUBLISHED && (command.brandId == null || command.categoryId == null)) {
            return Decision.reject(Rejections.INCOMPLETE, "A published product keeps its brand and category.")
        }
        val unchanged = command.name == state.name &&
            command.description == state.description &&
            command.brandId == state.brandId &&
            command.categoryId == state.categoryId
        if (unchanged) return Decision.NoOp
        return Decision.events(ProductUpdated(command.name, command.description, command.brandId, command.categoryId))
    }

    @OnCommand(produces = [ProductPriceChanged::class])
    fun onCommand(command: ChangeProductPrice): Decision = when {
        command.price.compareTo(state.price) == 0 -> Decision.NoOp
        else -> Decision.events(ProductPriceChanged(command.price, previous = state.price))
    }

    @Suppress("UnusedParameter")
    @OnCommand(produces = [ProductPublished::class])
    fun onCommand(command: PublishProduct): Decision = when {
        state.status == ProductStatus.PUBLISHED -> Decision.NoOp
        state.brandId == null || state.categoryId == null ->
            Decision.reject(Rejections.INCOMPLETE, "The product needs a brand and a category.")

        else -> Decision.events(ProductPublished)
    }

    @OnCommand(produces = [ProductDelisted::class])
    fun onCommand(command: DelistProduct): Decision =
        if (state.status != ProductStatus.PUBLISHED) {
            Decision.reject(Rejections.NOT_PUBLISHED, "The product is ${state.status}.")
        } else {
            Decision.events(ProductDelisted(command.reason))
        }

    /**
     * Discontinues the product for good: its own event, with the reason, and the lifecycle event, in one commit, so
     * the product is deleted as `DeleteAggregate` deletes it and the reason stays in its history (decision D336).
     */
    @OnCommand(produces = [ProductDiscontinued::class, AggregateDeleted::class])
    fun onCommand(command: DiscontinueProduct): Decision =
        Decision.events(ProductDiscontinued(command.reason), AggregateDeleted)

    /**
     * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
     * `<context>.product.<code>`, as its peers read them from the api's generated contract.
     */
    object Rejections {
        @ErrorCode(description = "A published product has a brand and a category.", status = 409)
        const val INCOMPLETE = "incomplete"

        @ErrorCode(description = "Only a published product can be delisted.", status = 409)
        const val NOT_PUBLISHED = "not-published"
    }
}
