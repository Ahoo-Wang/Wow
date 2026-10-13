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

package me.ahoo.wow.example.inventory.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The inventory served by the Wow host (decision D144), a service of its own beside the shop: the host finds the stock
 * model and the order saga through `inventory-domain`'s binding index, executes the commands other contexts send to
 * `wow.inventory.commands` (`wow.kafka.command-intake`), and reads the shop's order stream. Without a profile it runs on
 * the memory backend; the `postgres` profile gives it a PostgreSQL `DataSource` ([PostgresDataSource]).
 */
@SpringBootApplication(proxyBeanMethods = false)
class InventoryServer

fun main(args: Array<String>) {
    runApplication<InventoryServer>(*args)
}
