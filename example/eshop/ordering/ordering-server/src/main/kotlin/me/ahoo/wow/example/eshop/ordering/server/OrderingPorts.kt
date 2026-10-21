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

import me.ahoo.wow.example.eshop.ordering.domain.order.BuyerNotifications
import me.ahoo.wow.example.eshop.ordering.domain.order.OrderSummaries
import me.ahoo.wow.example.eshop.ordering.domain.order.OrderSummary
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The order summaries in the service's memory, newest version first. The shop has no order history page, so the
 * summaries are not served; a deployment that has one binds [OrderSummaries] to the store that page reads (a table
 * written with `... WHERE version < :version`, say), as this one keeps the newest version of each order.
 */
class InMemoryOrderSummaries : OrderSummaries {
    private val summaries = ConcurrentHashMap<Pair<String, String>, Versioned>()

    override fun save(summary: OrderSummary, version: Int) {
        summaries.merge(summary.tenantId to summary.orderId, Versioned(version, summary)) { saved, newer ->
            if (newer.version > saved.version) newer else saved
        }
    }

    /** The summary of the order [orderId] in [tenantId], or null when none was saved. */
    fun summaryOf(tenantId: String, orderId: String): OrderSummary? = summaries[tenantId to orderId]?.summary

    private data class Versioned(val version: Int, val summary: OrderSummary)
}

/**
 * The buyers' notifications written to the service's log: the shop sends no mail. A deployment binds
 * [BuyerNotifications] to its mail or push service, passing the key on as the service's idempotency key.
 */
class LoggedBuyerNotifications : BuyerNotifications {
    override suspend fun orderShipped(buyerId: String, orderId: String, key: String) {
        log.info("The order {} of {} is on its way (notification {}).", orderId, buyerId, key)
    }

    private companion object {
        val log = LoggerFactory.getLogger(LoggedBuyerNotifications::class.java)
    }
}
