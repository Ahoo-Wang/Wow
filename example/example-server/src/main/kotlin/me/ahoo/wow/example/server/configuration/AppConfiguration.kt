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

package me.ahoo.wow.example.server.configuration

import me.ahoo.wow.example.domain.order.CreateOrderSpec
import me.ahoo.wow.example.domain.order.DefaultCreateOrderSpec
import me.ahoo.wow.example.domain.order.infra.InventoryService
import me.ahoo.wow.example.domain.order.infra.PricingService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono
import java.math.BigDecimal

@Configuration
class AppConfiguration {

    @Bean
    fun createOrderSpecification(): CreateOrderSpec {
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String): Mono<Int> {
                return Mono.just(100)
            }
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String): Mono<BigDecimal> {
                return Mono.just(BigDecimal.valueOf(10))
            }
        }
        return DefaultCreateOrderSpec(inventoryService, pricingService)
    }
}
