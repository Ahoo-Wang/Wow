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

package me.ahoo.wow.example.eshop.ordering.domain.order

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Reactor
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.subscribe.SubscriptionContext
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped

/**
 * How buyers are told about their orders: a mail or push service outside Wow. A port of the [ShipmentNotifier], which
 * the server binds to that service and a test fakes.
 */
fun interface BuyerNotifications {
    /**
     * Tells [buyerId] that the order [orderId] is on its way. [key] identifies the notification: the service sends it
     * once per key, so a notification asked for again after a failure, a replay or a lease handover is not repeated.
     *
     * @throws UnknownBuyerException when the service has no buyer [buyerId].
     */
    suspend fun orderShipped(buyerId: String, orderId: String, key: String)
}

/** The notification service has no buyer [buyerId]: asking again does not help. */
class UnknownBuyerException(val buyerId: String) : RuntimeException("The notification service has no buyer $buyerId.")

// region notifier
/**
 * Tells the buyer of a shipped order that it is on its way. The notification service is a remote call that may be
 * slow or briefly down, so the reactor gives it its own budget: five attempts, from one second apart up to thirty, and
 * ten seconds for each, before the order is parked for the handler in the failure ledger. A buyer the service does not
 * know is a failure no retry can fix, so it parks the order at once.
 *
 * A reactor starts at the end of the log: deploying it does not notify the buyers of the orders shipped before.
 */
@Reactor
@Retry(
    maxAttempts = 5,
    initialBackoff = "1s",
    maxBackoff = "30s",
    timeout = "10s",
    unrecoverable = [UnknownBuyerException::class],
)
class ShipmentNotifier(private val notifications: BuyerNotifications) {
    @OnEvent
    suspend fun onOrderShipped(event: StateTypedEvent<OrderState, OrderShipped>, context: SubscriptionContext) {
        val buyer = event.scope.ownerId ?: return
        notifications.orderShipped(buyer, event.ref.id, key = context.invocationKey)
    }
}
// endregion
