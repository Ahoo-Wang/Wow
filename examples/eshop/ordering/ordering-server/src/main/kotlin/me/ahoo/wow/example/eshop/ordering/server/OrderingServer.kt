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

package me.ahoo.wow.example.eshop.ordering.server

import me.ahoo.cosid.store.StoreProvider
import me.ahoo.wow.example.eshop.ordering.domain.order.PaymentPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * The eShop's ordering service on the Wow host: the host finds the order model and the sagas through
 * `eshop-ordering-domain`'s binding index and serves its routes on the public port and the cluster on the internal
 * one. It runs on the memory backend by default and on PostgreSQL with the `postgres` profile (`spring.datasource.*`).
 *
 * It numbers the orders it creates (decision D373): CosId's generator `eshop-ordering.order` gives each new order its id,
 * `SO-<yyMMdd>-<sequence>`, from ranges each node reserves in one store (`eshop-ordering-ids.yaml`).
 *
 * Its sagas send the inventory's commands, so the node needs a command transport: the `kafka` profile gives it Kafka,
 * and it does not start without one.
 */
@SpringBootApplication(proxyBeanMethods = false)
class OrderingServer {
    /** The order's payment grace period, `eshop.ordering.payment-grace-period` (30 minutes by default). */
    @Bean
    fun paymentPolicy(@Value("\${eshop.ordering.payment-grace-period:30m}") gracePeriod: Duration): PaymentPolicy =
        PaymentPolicy(gracePeriod.toKotlinDuration())

    /** Where the order-summary projection keeps the buyers' order history: the service's memory here. */
    @Bean
    fun orderSummaries(): InMemoryOrderSummaries = InMemoryOrderSummaries()

    /**
     * The order numbers' ranges in the service's memory, the memory backend's store (`cosid.stores.orders.type:
     * memory`); the `postgres` and `mongo` profiles keep them in the service's database.
     */
    @Bean
    fun memoryRangeStores(): StoreProvider = MemoryRangeStores()

    /** How the shipment notifier tells a buyer: the service's log here. */
    @Bean
    fun buyerNotifications(): LoggedBuyerNotifications = LoggedBuyerNotifications()
}

fun main(args: Array<String>) {
    runApplication<OrderingServer>(*args)
}
