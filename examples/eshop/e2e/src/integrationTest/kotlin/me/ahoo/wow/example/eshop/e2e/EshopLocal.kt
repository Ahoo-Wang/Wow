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

import me.ahoo.wow.example.eshop.basket.server.BasketServer
import me.ahoo.wow.example.eshop.catalog.server.CatalogServer
import me.ahoo.wow.example.eshop.inventory.server.InventoryServer
import me.ahoo.wow.example.eshop.ordering.server.OrderingServer
import me.ahoo.wow.example.eshop.payment.server.PaymentServer
import me.ahoo.wow.test.server.WowLocal
import me.ahoo.wow.test.server.runWowLocal

/**
 * The whole eShop in this one JVM, on the memory backend and without Kafka, for the web UI (work package E.7) and for
 * trying the shop out: `./gradlew :eshop-e2e:eshopLocal`. The five services share one class path, each serving its own
 * context, and one set of topics in memory (`runWowLocal`, DX gap G27): each one's sagas send other contexts' commands
 * through it, its published streams are written to it, and it executes the commands sent to its own command topic. So
 * the checkout's whole chain runs, to the payment, as it does on Kafka.
 *
 * The system property `wow-local.ports` is `fixed` (the default: the README's ports, 8081 to 8085 public and 9091 to
 * 9095 internal) or `random` (every port bound to 0). Once every service listens, it prints one line,
 * `wow-local ready {"eshop-inventory-service":8084,...}`, with the public ports. With
 * `wow-local.exit-on-stdin-eof=true` it stops when its standard input closes, so a test that started it never leaves
 * it running.
 */
fun main() = runWowLocal { eshop() }

/**
 * The five eShop services, each called service before its callers: the ordering before the basket, whose checkout
 * calls it, so the basket's `wow.peers.eshop-ordering.url` names the ordering's internal port.
 */
fun WowLocal.Builder.eshop() {
    service<InventoryServer>("eshop-inventory-service", listOf("eshop-inventory"), 8084, 9094, settingsOf("inventory"))
    service<OrderingServer>(
        "eshop-ordering-service",
        listOf("eshop-ordering"),
        8083,
        9093,
        settingsOf("ordering", "eshop.ordering.payment-grace-period" to "30m"),
    )
    service<PaymentServer>(
        "eshop-payment-service",
        listOf("eshop-payment"),
        8085,
        9095,
        settingsOf("payment", "eshop.payment.gateway.limit" to "1000.00"),
    )
    service<BasketServer>("eshop-basket-service", listOf("eshop-basket"), 8082, 9092, settingsOf("basket"))
    service<CatalogServer>("eshop-catalog-service", listOf("eshop-catalog"), 8081, 9091, settingsOf("catalog"))
}

/**
 * The settings of the eShop service of [context] in a JVM that runs several services on one class path, where Spring
 * Boot reads one `application.yaml` for all of them: its CosId setting ([cosIdOf]), its own [settings], and the log
 * kept to warnings.
 */
private fun settingsOf(context: String, vararg settings: Pair<String, Any?>): Map<String, Any?> =
    mapOf("logging.level.root" to "WARN", cosIdOf(context)) + settings

/**
 * The CosId setting of the eShop service of [context] in a JVM that runs several services on one class path, where
 * Spring Boot reads one `application.yaml` for all of them: the ordering imports its order numbers' configuration
 * (decision D373); the others find CosId on the ordering's class path, and run without it.
 */
fun cosIdOf(context: String): Pair<String, String> =
    if (context == "ordering") {
        "spring.config.import" to "classpath:eshop-ordering-ids.yaml"
    } else {
        "cosid.enabled" to "false"
    }
