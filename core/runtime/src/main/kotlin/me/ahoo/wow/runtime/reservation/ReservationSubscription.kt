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

package me.ahoo.wow.runtime.reservation

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.reservation.ReservationStore

/**
 * The built-in `reservation` subscription of one bounded [context] (decision D50, design 01 §7): confirms the keys a
 * committed entry reserved pending, which the kernel records in the entry's transport
 * ([me.ahoo.wow.spi.log.Transport.reservations]). The kernel's after-append confirmation is only the fast path; this
 * subscription confirms the keys when it never ran, because the process crashed between the append and the
 * confirmation or the confirmation failed, so a committed fact does not lose its key to the pending reservation's
 * expiry.
 *
 * Register [subscription] in the context's [me.ahoo.wow.runtime.subscription.SubscriptionRuntime]: it then runs on
 * each partition's owner, checkpoints and parks like any subscription. A store that keeps failing parks the
 * aggregate; resuming it reads the aggregate's entries again with their transport
 * ([me.ahoo.wow.spi.log.EventLog.readEntries]) and confirms what is left.
 *
 * - **Owner**: the entry's aggregate ([me.ahoo.wow.api.aggregate.AggregateRef.toString]), as the kernel reserves.
 * - **Idempotent**: confirming a key its owner holds confirmed changes nothing, and [ReservationStore.confirm] does
 *   nothing for anyone else, so a replay, or an old owner running beside the new one (decision D45), never takes a
 *   key from the owner that holds it now. A key the entry's aggregate no longer holds (released since, or taken by
 *   another owner after the pending reservation expired) is logged and passed over: confirming cannot get it back.
 * - **Expiry**: a pending reservation that no entry carries (the attempt crashed before appending, finding R8) is
 *   never confirmed; it expires in the store, and the next reservation of the key takes it over.
 */
@InternalWowApi
class ReservationSubscription(context: String, private val store: ReservationStore) {
    /** The subscription to register: named [Hop.RESERVATION_SUBSCRIPTION], of every aggregate type of the context. */
    val subscription: Subscription = Subscription.builtIn(
        Hop.RESERVATION_SUBSCRIPTION,
        SubscriptionFilter(context),
        FunctionKind.RESERVATION,
        ::confirm,
    )

    private suspend fun confirm(entry: SubscribedEntry) {
        val keys = entry.transport?.reservations.orEmpty()
        if (keys.isEmpty()) {
            return
        }
        val owner = entry.ref.toString()
        for (key in keys) {
            if (!store.confirm(key.namespace, key.key, owner)) {
                logger.warn {
                    "${entry.ref}@${entry.version} reserved ${key.namespace}/${key.key}, which it no longer holds: " +
                        "released since, or taken by another owner after its pending reservation expired."
                }
            }
        }
    }
}

private val logger = KotlinLogging.logger {}
