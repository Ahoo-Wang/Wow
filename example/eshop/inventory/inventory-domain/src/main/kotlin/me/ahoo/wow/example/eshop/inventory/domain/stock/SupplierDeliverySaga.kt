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

package me.ahoo.wow.example.eshop.inventory.domain.stock

import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.OnSchedule
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.subscribe.JobContext
import me.ahoo.wow.example.eshop.inventory.api.stock.ReceiveStock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A supplier's delivery to the warehouse: [quantity] units of the product [productId]. */
data class Delivery(val productId: String, val quantity: Int)

/**
 * The suppliers' delivery feed: what they delivered to the warehouse, day by day. A port of the
 * [SupplierDeliverySaga], which the server binds to the warehouse's receiving system and a test fakes.
 */
fun interface SupplierDeliveries {
    /** The deliveries the warehouse of [tenantId] received on [day] (UTC), in the order they arrived. */
    fun deliveredOn(tenantId: String, day: LocalDate): List<Delivery>
}

/**
 * Receives the suppliers' deliveries into stock once a day. Every morning at 06:00 UTC, the job reads the deliveries
 * of the day before from [SupplierDeliveries] and sends a [ReceiveStock] to each delivered product's stock.
 *
 * - A job of one instance for the service ([me.ahoo.wow.api.schedule.JobScope.SYSTEM], the default), enabled by the
 *   host at startup, in the default tenant: the shop's one warehouse.
 * - A tick that coalesced missed slots (no node ran the job for a few days) reads each missed day too, so no delivery
 *   is left out.
 * - The commands' ids come from the tick's execution, so handling the tick again, after a failure, sends the same
 *   requests, which each stock decides once.
 * - Reading the feed blocks, so the job runs on a virtual thread.
 */
@Saga
class SupplierDeliverySaga(private val deliveries: SupplierDeliveries) {
    // region job
    @Blocking
    @OnSchedule(name = "receive-deliveries", cron = "0 0 6 * * *")
    fun onSchedule(tick: ScheduleTick, context: JobContext): List<Command<ReceiveStock>> {
        val today = Instant.ofEpochMilli(tick.due).atZone(ZoneOffset.UTC).toLocalDate()
        val days = (tick.skipped + 1 downTo 1).map { today.minusDays(it) }
        return days.flatMap { day -> deliveries.deliveredOn(tick.tenantId, day) }.map { delivery ->
            // The stock of the product, in the job instance's tenant: ReceiveStock names its aggregate type.
            context.command(delivery.productId, ReceiveStock(delivery.quantity))
        }
    }
    // endregion
}
