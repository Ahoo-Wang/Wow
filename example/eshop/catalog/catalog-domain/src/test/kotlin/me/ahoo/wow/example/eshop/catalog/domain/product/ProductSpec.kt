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
import me.ahoo.wow.example.eshop.catalog.api.product.CreateProduct
import me.ahoo.wow.example.eshop.catalog.api.product.ProductCreated
import me.ahoo.wow.kernel.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The product on the kernel, through the model the compiler generated. */
class ProductSpec {
    @Test
    fun `creating a product should add it to the catalog`() {
        aggregateSpec(productModel(CatalogContext.NAME), id = "product-1") {
            whenCommand(CreateProduct("Wow mug", BigDecimal("8.50"))) {
                expectEvents(ProductCreated("Wow mug", BigDecimal("8.50")))
                expectState {
                    name.assert().isEqualTo("Wow mug")
                    price.assert().isEqualByComparingTo("8.50")
                }
            }
        }
    }
}
