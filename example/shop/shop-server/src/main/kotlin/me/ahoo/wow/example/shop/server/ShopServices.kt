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

package me.ahoo.wow.example.shop.server

import me.ahoo.wow.example.shop.domain.order.PricingService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * The service placing an order consults, as a bean: the host passes it to the order model's factory, found by type
 * (decision D107). Products live outside the shop; this stub serves a fixed [Catalog]. Their stock is the inventory
 * service's, which the order saga reserves (decision D144).
 */
@Configuration(proxyBeanMethods = false)
class ShopServices {
    @Bean
    fun pricingService(): PricingService = CatalogPricingService(Catalog.DEFAULT)
}

/** A product of the [Catalog]: its unit [price]. */
data class Product(val price: BigDecimal)

/** The products the stub services know, by id. */
data class Catalog(val products: Map<String, Product>) {
    companion object {
        val DEFAULT: Catalog = Catalog(
            mapOf(
                "apple" to Product(BigDecimal("2.50")),
                "pear" to Product(BigDecimal("4.00")),
                "durian" to Product(BigDecimal("30.00")),
            ),
        )
    }
}

/** The price of the catalog's products; a product it does not list has no price. */
class CatalogPricingService(private val catalog: Catalog) : PricingService {
    override suspend fun getProductPrice(productId: String): BigDecimal =
        requireNotNull(catalog.products[productId]) { "No product $productId." }.price
}
