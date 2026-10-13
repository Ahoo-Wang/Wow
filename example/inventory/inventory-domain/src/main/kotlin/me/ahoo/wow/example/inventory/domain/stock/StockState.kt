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

package me.ahoo.wow.example.inventory.domain.stock

import me.ahoo.wow.example.inventory.api.ReservationCommitted
import me.ahoo.wow.example.inventory.api.ReservationReleased
import me.ahoo.wow.example.inventory.api.StockReceived
import me.ahoo.wow.example.inventory.api.StockReserved

/**
 * A product's stock, changed in place by its events: [onHand] units in the warehouse, of which [reservations] hold
 * some for orders not shipped yet (by order id), and [available] are free to reserve.
 */
class StockState(val id: String) {
    var onHand: Int = 0
        private set
    var available: Int = 0
        private set
    var reservations: Map<String, Int> = emptyMap()
        private set

    fun onSourcing(event: StockReceived) {
        onHand += event.quantity
        available += event.quantity
    }

    fun onSourcing(event: StockReserved) {
        available -= event.quantity
        reservations = reservations + (event.orderId to event.quantity)
    }

    fun onSourcing(event: ReservationCommitted) {
        onHand -= event.quantity
        reservations = reservations - event.orderId
    }

    fun onSourcing(event: ReservationReleased) {
        available += event.quantity
        reservations = reservations - event.orderId
    }
}
