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

package me.ahoo.wow.example.eshop.basket.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The eShop's basket service on the Wow host: the host finds the basket model through `eshop-basket-domain`'s binding
 * index and serves its routes on the public port and the cluster on the internal one. It runs on the memory backend by
 * default and on PostgreSQL with the `postgres` profile ([PostgresDataSource]).
 *
 * Its checkout saga sends the ordering context's commands, so the node needs a command transport: the `kafka` profile
 * gives it Kafka, and it does not start without one.
 */
@SpringBootApplication(proxyBeanMethods = false)
class BasketServer

fun main(args: Array<String>) {
    runApplication<BasketServer>(*args)
}
