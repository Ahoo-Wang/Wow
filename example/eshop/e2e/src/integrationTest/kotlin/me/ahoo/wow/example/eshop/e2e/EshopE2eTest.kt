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
import org.junit.jupiter.api.Test

/**
 * The skeleton of the eShop's end-to-end suite (work package E.0): the catalog and basket services, each in a JVM of
 * its own on the memory backend, serve their own context only and accept a command each.
 */
class EshopE2eTest {
    @Test
    fun `two services should each serve their own context and accept its command`() {
        EshopService.start("catalog", CATALOG_MAIN).use { catalog ->
            EshopService.start("basket", BASKET_MAIN).use { basket ->
                catalog.contexts().assert().containsExactly("eshop-catalog")
                basket.contexts().assert().containsExactly("eshop-basket")

                val product = catalog.post("/eshop-catalog/product/create-product", """{"name":"Wow mug","price":8.50}""")
                product.status.assert().describedAs(product.body).isEqualTo(200)
                val productId = json.readTree(product.body)["ref"]["id"].asString()
                productId.assert().isNotBlank()

                val basketItem = basket.post(
                    "/eshop-basket/basket/alice/add-basket-item",
                    """{"productId":"$productId","quantity":2}""",
                )
                basketItem.status.assert().describedAs(basketItem.body).isEqualTo(200)
                json.readTree(basketItem.body)["ref"]["id"].asString().assert().isEqualTo("alice")
            }
        }
    }

    private companion object {
        const val CATALOG_MAIN = "me.ahoo.wow.example.eshop.catalog.server.CatalogServerKt"
        const val BASKET_MAIN = "me.ahoo.wow.example.eshop.basket.server.BasketServerKt"
    }
}
