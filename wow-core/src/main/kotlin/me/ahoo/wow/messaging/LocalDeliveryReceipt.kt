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

package me.ahoo.wow.messaging

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.messaging.handler.MessageExchange
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val LOCAL_DELIVERY_TICKET_ATTRIBUTE = "__WOW_LOCAL_DELIVERY_TICKET__"

/**
 * Confirms that every targeted local receiver acquired runtime admission.
 *
 * Rejection wins over incomplete confirmations. Once every target confirms,
 * the receipt is terminal and later route shutdown cannot revoke admitted work.
 * Sender continuation crosses an asynchronous boundary so route shutdown never
 * runs a distributed fallback on its lifecycle thread.
 */
internal class LocalDeliveryReceipt(targets: Set<LocalDeliveryRouteTarget>) {
    private val monitor = Any()
    private val remainingTargets = targets.toMutableSet()
    private val unclaimedTargets = targets.toMutableSet()
    private val result = Sinks.one<Boolean>()
    private var terminal = false

    init {
        require(targets.isNotEmpty()) {
            "targets must not be empty."
        }
    }

    fun signal(): Mono<Boolean> =
        result.asMono()
            .publishOn(Schedulers.parallel())

    fun claim(target: LocalDeliveryRouteTarget): LocalDeliveryTicket? =
        synchronized(monitor) {
            if (terminal || !unclaimedTargets.remove(target)) {
                null
            } else {
                LocalDeliveryTicket(this, target)
            }
        }

    fun confirm(target: LocalDeliveryRouteTarget) {
        val completed = synchronized(monitor) {
            if (terminal || !remainingTargets.remove(target)) {
                false
            } else if (remainingTargets.isEmpty()) {
                terminal = true
                true
            } else {
                false
            }
        }
        if (completed) {
            result.tryEmitValue(true)
        }
    }

    fun reject() {
        val rejected = synchronized(monitor) {
            if (terminal) {
                false
            } else {
                terminal = true
                remainingTargets.clear()
                unclaimedTargets.clear()
                true
            }
        }
        if (rejected) {
            result.tryEmitValue(false)
        }
    }
}

/**
 * Opaque identity of one managed local receiver route.
 */
internal class LocalDeliveryRouteTarget

/**
 * One-shot admission decision for one route in one local delivery.
 */
internal class LocalDeliveryTicket(
    private val receipt: LocalDeliveryReceipt,
    private val target: LocalDeliveryRouteTarget,
) {
    private val decided = AtomicBoolean()

    fun confirm() {
        if (decided.compareAndSet(false, true)) {
            receipt.confirm(target)
        }
    }

    fun reject() {
        if (decided.compareAndSet(false, true)) {
            receipt.reject()
        }
    }
}

internal class LocalDeliveryRoute<M : Message<*, *>> {
    private val monitor = Any()
    private val subscriptions = mutableMapOf<LocalDeliveryRouteTarget, Boolean>()
    private val pendingDeliveries = IdentityHashMap<M, PendingLocalDelivery<M>>()

    fun unavailableSubscriptions(): Int =
        synchronized(monitor) {
            subscriptions.count { !it.value }
        }

    fun tryCreateDelivery(
        message: M,
        physicalSubscribers: Int,
        messageWritable: Boolean,
    ): PendingLocalDelivery<M>? =
        synchronized(monitor) {
            if (!canCreateDelivery(message, physicalSubscribers, messageWritable)) {
                return@synchronized null
            }
            PendingLocalDelivery(
                message = message,
                receipt = LocalDeliveryReceipt(subscriptions.keys),
            ).also {
                pendingDeliveries[message] = it
            }
        }

    private fun canCreateDelivery(
        message: M,
        physicalSubscribers: Int,
        messageWritable: Boolean,
    ): Boolean =
        subscriptions.isNotEmpty() &&
            subscriptions.values.all { it } &&
            physicalSubscribers >= subscriptions.size &&
            messageWritable &&
            !pendingDeliveries.containsKey(message)

    fun ticket(
        message: M,
        target: LocalDeliveryRouteTarget,
    ): LocalDeliveryTicket? =
        synchronized(monitor) {
            pendingDeliveries[message]?.receipt?.claim(target)
        }

    fun addSubscription(
        target: LocalDeliveryRouteTarget,
        open: Boolean,
    ): List<PendingLocalDelivery<M>> =
        synchronized(monitor) {
            check(subscriptions.putIfAbsent(target, open) == null) {
                "Local routing target is already subscribed."
            }
            drainPending()
        }

    fun openSubscription(target: LocalDeliveryRouteTarget): List<PendingLocalDelivery<M>> =
        synchronized(monitor) {
            check(subscriptions[target] == false) {
                "Local routing target is missing or already open."
            }
            subscriptions[target] = true
            drainPending()
        }

    fun closeSubscription(target: LocalDeliveryRouteTarget): List<PendingLocalDelivery<M>> =
        synchronized(monitor) {
            check(subscriptions[target] == true) {
                "Local routing target is missing or already closed."
            }
            subscriptions[target] = false
            drainPending()
        }

    fun removeSubscription(target: LocalDeliveryRouteTarget): List<PendingLocalDelivery<M>> =
        synchronized(monitor) {
            check(subscriptions.remove(target) != null) {
                "Local routing target is not subscribed."
            }
            drainPending()
        }

    fun remove(delivery: PendingLocalDelivery<M>) {
        synchronized(monitor) {
            if (pendingDeliveries[delivery.message] === delivery) {
                pendingDeliveries.remove(delivery.message)
            }
        }
    }

    fun reject(delivery: PendingLocalDelivery<M>) {
        remove(delivery)
        delivery.receipt.reject()
    }

    fun drain(): List<PendingLocalDelivery<M>> =
        synchronized(monitor) {
            drainPending()
        }

    private fun drainPending(): List<PendingLocalDelivery<M>> {
        if (pendingDeliveries.isEmpty()) {
            return emptyList()
        }
        return pendingDeliveries.values.toList().also {
            pendingDeliveries.clear()
        }
    }
}

internal class PendingLocalDelivery<M : Message<*, *>>(
    val message: M,
    val receipt: LocalDeliveryReceipt,
)

internal fun <M : Message<*, *>> List<PendingLocalDelivery<M>>.rejectAll() {
    forEach {
        it.receipt.reject()
    }
}

internal fun MessageExchange<*, *>.attachLocalDeliveryTicket(ticket: LocalDeliveryTicket) {
    setAttribute(LOCAL_DELIVERY_TICKET_ATTRIBUTE, ticket)
}

internal fun MessageExchange<*, *>.takeLocalDeliveryTicket(): LocalDeliveryTicket? {
    val ticket = getAttribute<LocalDeliveryTicket>(LOCAL_DELIVERY_TICKET_ATTRIBUTE)
    removeAttribute(LOCAL_DELIVERY_TICKET_ATTRIBUTE)
    return ticket
}

/**
 * Confirms that this receiver synchronously admitted the local exchange.
 *
 * Custom consumers of [InMemoryMessageBus.runtimeReceiver] call this only
 * after their runtime admission and durable in-process handoff succeed.
 * Built-in dispatchers perform the confirmation automatically. The operation
 * is idempotent and is a no-op for exchanges without a local delivery ticket.
 */
fun MessageExchange<*, *>.confirmLocalDelivery() {
    takeLocalDeliveryTicket()?.confirm()
}

/**
 * Rejects local-only suppression when this exchange is filtered or cannot be
 * admitted. The distributed copy then remains eligible for processing.
 *
 * The operation is idempotent and is a no-op for exchanges without a local
 * delivery ticket.
 */
fun MessageExchange<*, *>.rejectLocalDelivery() {
    takeLocalDeliveryTicket()?.reject()
}
