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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.catalog.api.CatalogContext
import me.ahoo.wow.example.eshop.catalog.api.product.ChangeProductPrice
import me.ahoo.wow.example.eshop.catalog.api.product.CreateProduct
import me.ahoo.wow.example.eshop.catalog.api.product.DelistProduct
import me.ahoo.wow.example.eshop.catalog.api.product.ProductCreated
import me.ahoo.wow.example.eshop.catalog.api.product.ProductDelisted
import me.ahoo.wow.example.eshop.catalog.api.product.ProductPriceChanged
import me.ahoo.wow.example.eshop.catalog.api.product.ProductPublished
import me.ahoo.wow.example.eshop.catalog.api.product.ProductStatus
import me.ahoo.wow.example.eshop.catalog.api.product.ProductUpdated
import me.ahoo.wow.example.eshop.catalog.api.product.PublishProduct
import me.ahoo.wow.example.eshop.catalog.api.product.UpdateProduct
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The product on the kernel, through the model the compiler generated. */
class ProductSpec {
    private val model = productModel(CatalogContext.NAME)
    private val mug = ProductCreated("Wow mug", BigDecimal("8.50"), "A mug", brandId = "wow", categoryId = "mugs")
    private val draft = ProductCreated("Wow mug", BigDecimal("8.50"))

    @Test
    fun `creating a product should add a draft to the catalog`() = aggregateSpec(model, id = "mug") {
        whenCommand(CreateProduct("Wow mug", BigDecimal("8.50"), "A mug", brandId = "wow", categoryId = "mugs")) {
            expectEvents(mug)
            expectState {
                name.assert().isEqualTo("Wow mug")
                description.assert().isEqualTo("A mug")
                price.assert().isEqualByComparingTo("8.50")
                brandId.assert().isEqualTo("wow")
                categoryId.assert().isEqualTo("mugs")
                status.assert().isEqualTo(ProductStatus.DRAFT)
            }
        }
    }

    @Test
    fun `creating a product should need a name and a positive price`() = aggregateSpec(model, id = "mug") {
        whenCommand(CreateProduct(" ", BigDecimal("8.50"))) { expectRejected(ProductRejections.BLANK_NAME) }
        whenCommand(CreateProduct("Wow mug", BigDecimal.ZERO)) { expectRejected(ProductRejections.INVALID_PRICE) }
    }

    @Test
    fun `creating a product twice should be refused`() = aggregateSpec(model, id = "mug") {
        given(draft)
        whenCommand(CreateProduct("Wow mug", BigDecimal("8.50"))) { expectRejected("wow.aggregate.exists") }
    }

    @Test
    fun `updating a product should replace its name, description, brand and category`() =
        aggregateSpec(model, id = "mug") {
            given(draft)
            whenCommand(UpdateProduct("Big mug", "Half a litre", brandId = "wow", categoryId = "mugs")) {
                expectEvents(ProductUpdated("Big mug", "Half a litre", "wow", "mugs"))
                expectState {
                    name.assert().isEqualTo("Big mug")
                    brandId.assert().isEqualTo("wow")
                }
            }
            whenCommand(UpdateProduct("Big mug", "Half a litre", brandId = "wow", categoryId = "mugs")) {
                expectUnchanged()
            }
            whenCommand(UpdateProduct("", "")) { expectRejected(ProductRejections.BLANK_NAME) }
        }

    @Test
    fun `a published product should keep its brand and category`() = aggregateSpec(model, id = "mug") {
        given(mug)
        given(ProductPublished)
        whenCommand(UpdateProduct("Wow mug", "A mug", brandId = null, categoryId = "mugs")) {
            expectRejected(ProductRejections.INCOMPLETE)
        }
        whenCommand(UpdateProduct("Wow cup", "A mug", brandId = "wow", categoryId = "cups")) {
            expectEvents(ProductUpdated("Wow cup", "A mug", "wow", "cups"))
        }
    }

    @Test
    fun `changing the price should record the previous one`() = aggregateSpec(model, id = "mug") {
        given(mug)
        whenCommand(ChangeProductPrice(BigDecimal("9.00"))) {
            expectEvents(ProductPriceChanged(BigDecimal("9.00"), previous = BigDecimal("8.50")))
            expectState { price.assert().isEqualByComparingTo("9.00") }
        }
        whenCommand(ChangeProductPrice(BigDecimal("9.000"))) { expectUnchanged() }
        whenCommand(ChangeProductPrice(BigDecimal("-1"))) { expectRejected(ProductRejections.INVALID_PRICE) }
    }

    @Test
    fun `publishing should need a brand and a category`() = aggregateSpec(model, id = "mug") {
        given(draft)
        whenCommand(PublishProduct) { expectRejected(ProductRejections.INCOMPLETE) }
        whenCommand(UpdateProduct("Wow mug", brandId = "wow", categoryId = "mugs")) {
            expectEventTypes(ProductUpdated::class.java)
        }
        whenCommand(PublishProduct) {
            expectEvents(ProductPublished)
            expectState { status.assert().isEqualTo(ProductStatus.PUBLISHED) }
        }
        whenCommand(PublishProduct) { expectUnchanged() }
    }

    @Test
    fun `a published product should be delisted and published again`() = aggregateSpec(model, id = "mug") {
        given(mug)
        whenCommand(DelistProduct()) { expectRejected(ProductRejections.NOT_PUBLISHED) }
        given(ProductPublished)
        whenCommand(DelistProduct("Out of season")) {
            expectEvents(ProductDelisted("Out of season"))
            expectState { status.assert().isEqualTo(ProductStatus.DELISTED) }
        }
        whenCommand(DelistProduct()) { expectRejected(ProductRejections.NOT_PUBLISHED) }
        whenCommand(PublishProduct) {
            expectEvents(ProductPublished)
            expectState { status.assert().isEqualTo(ProductStatus.PUBLISHED) }
        }
    }
}
