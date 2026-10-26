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

package me.ahoo.wow.example.eshop.inventory.server

import me.ahoo.wow.example.eshop.inventory.domain.stock.SupplierDeliveries
import me.ahoo.wow.http.caller.CallerResolver
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * The eShop's inventory service on the Wow host: the host finds the stock model through `eshop-inventory-domain`'s binding
 * index and serves its routes on the public port and the cluster on the internal one. It runs on the memory backend by
 * default, on PostgreSQL with the `postgres` profile (`spring.datasource.*`, decision D310), and takes the ordering context's commands
 * from Kafka with the `kafka` profile.
 */
@SpringBootApplication(proxyBeanMethods = false)
class InventoryServer {
    /**
     * The suppliers' delivery feed the delivery job reads each morning. The shop has no receiving system, so none
     * arrives; a deployment binds [SupplierDeliveries] to its warehouse's.
     */
    @Bean
    fun supplierDeliveries(): SupplierDeliveries = SupplierDeliveries { _, _ -> emptyList() }

    /**
     * Who calls the service: no one in particular. Its commands come from the ordering context's sagas over the
     * cluster, and its public routes serve the anonymous caller of the default tenant; outside development the host
     * wants that said in a bean (decision D301).
     */
    @Bean
    fun anonymousCallers(): CallerResolver = CallerResolver.ANONYMOUS
}

fun main(args: Array<String>) {
    runApplication<InventoryServer>(*args)
}
