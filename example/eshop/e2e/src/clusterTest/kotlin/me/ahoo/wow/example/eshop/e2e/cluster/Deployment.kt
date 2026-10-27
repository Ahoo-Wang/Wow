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

package me.ahoo.wow.example.eshop.e2e.cluster

/** What the scenarios file and reach: the catalog's products and stock, and every service's public and internal port. */
object Deployment {
    val PRODUCTS = mapOf(
        "mug" to "8.50",
        "cap" to "12.00",
        "tv" to "1500.00",
        "pen" to "2.00",
        "pad" to "3.00",
        "kite" to "20.00",
        "sock" to "5.00",
    )
    val STOCK = mapOf("mug" to 50, "cap" to 5, "tv" to 2, "pen" to 1, "pad" to 10, "kite" to 20, "sock" to 100_000)

    /** The ordering's Deployment, the one the cluster scenarios scale, restart and delete pods of. */
    const val ORDERING = "eshop-ordering-service"

    /** The public and internal ports, by Service name: the service's own (`spring.application.name`), as deploy/ names it. */
    val PUBLIC = listOf(
        "eshop-catalog-service",
        "eshop-basket-service",
        "eshop-ordering-service",
        "eshop-inventory-service",
        "eshop-payment-service",
        "ops-service",
        "view-store-service",
        "analytics-catalog-service",
    ).map { it to 80 }

    val INTERNAL = listOf(
        "eshop-catalog-service" to 9091,
        "eshop-basket-service" to 9092,
        "eshop-ordering-service" to 9093,
        "eshop-inventory-service" to 9094,
        "eshop-payment-service" to 9095,
        "ops-service" to 9098,
        "view-store-service" to 9090,
        "analytics-catalog-service" to 9097,
    )
}
