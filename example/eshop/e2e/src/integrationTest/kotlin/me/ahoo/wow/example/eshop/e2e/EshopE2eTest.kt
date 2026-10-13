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
 * The skeleton of the eShop's end-to-end suite (work package E.0): the catalog and inventory services, each in a JVM
 * of its own on the memory backend, serve their own context only and accept a command each. The basket is not one of
 * them: its checkout saga sends the ordering context's commands, so it starts only with a command transport
 * ([EshopOrderFlowTest] gives it Kafka).
 */
class EshopE2eTest {
    @Test
    fun `two services should each serve their own context and accept its command`() {
        EshopService.start("catalog", CATALOG_MAIN).use { catalog ->
            EshopService.start("inventory", INVENTORY_MAIN).use { inventory ->
                catalog.contexts().assert().containsExactly("eshop-catalog")
                inventory.contexts().assert().containsExactly("eshop-inventory")

                val product = catalog.post("/eshop-catalog/product/create-product", """{"name":"Wow mug","price":8.50}""")
                product.status.assert().describedAs(product.body).isEqualTo(200)
                val productId = json.readTree(product.body)["ref"]["id"].asString()
                productId.assert().isNotBlank()

                val stock = inventory.post("/eshop-inventory/stock/$productId/receive-stock", """{"quantity":10}""")
                stock.status.assert().describedAs(stock.body).isEqualTo(200)
                json.readTree(stock.body)["ref"]["id"].asString().assert().isEqualTo(productId)
            }
        }
    }

    private companion object {
        const val CATALOG_MAIN = "me.ahoo.wow.example.eshop.catalog.server.CatalogServerKt"
        const val INVENTORY_MAIN = "me.ahoo.wow.example.eshop.inventory.server.InventoryServerKt"
    }
}
