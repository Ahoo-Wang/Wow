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

import me.ahoo.wow.example.eshop.inventory.api.stock.ReservationCommitted
import me.ahoo.wow.example.eshop.inventory.api.stock.ReservationReleased
import me.ahoo.wow.example.eshop.inventory.api.stock.StockReceived
import me.ahoo.wow.example.eshop.inventory.api.stock.StockReserved

/**
 * The state of a [Stock], changed in place by its events: the units [onHand], those [reserved][reservations] for each
 * order, and the units still [available] to reserve. Every property has a value before the first event.
 */
class StockState {
    var onHand: Int = 0
        private set

    var available: Int = 0
        private set

    /** The units reserved for each order not yet shipped or cancelled, by the order's id. */
    var reservations: Map<String, Int> = emptyMap()
        private set

    fun onSourcing(event: StockReceived) {
        onHand += event.quantity
        available += event.quantity
    }

    fun onSourcing(event: StockReserved) {
        reservations = reservations + (event.orderId to event.quantity)
        available -= event.quantity
    }

    fun onSourcing(event: ReservationCommitted) {
        reservations = reservations - event.orderId
        onHand -= event.quantity
    }

    fun onSourcing(event: ReservationReleased) {
        reservations = reservations - event.orderId
        available += event.quantity
    }
}
