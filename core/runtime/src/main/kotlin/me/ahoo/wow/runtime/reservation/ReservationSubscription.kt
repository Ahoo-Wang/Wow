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
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore

/**
 * The built-in `reservation` subscription of one bounded [context]: confirms the keys a
 * committed entry reserved pending, then releases the keys it released, as the kernel records them in
 * the entry's transport ([me.ahoo.wow.spi.log.Transport.reservations], [me.ahoo.wow.spi.log.Transport.releases]).
 * The kernel's after-append actions are only the fast path; this subscription applies them when they never ran,
 * because the process crashed between the append and them or they failed, so a committed fact neither loses its key
 * to the pending reservation's expiry nor keeps a key it gave up.
 *
 * Register [subscription] in the context's [me.ahoo.wow.runtime.subscription.SubscriptionRuntime]: it then runs on
 * each partition's owner, checkpoints and parks like any subscription. A store that keeps failing parks the
 * aggregate; retrying it reads the aggregate's entries again with their transport
 * ([me.ahoo.wow.spi.log.EventLog.readEntries]) and applies what is left.
 *
 * - **Owner and scope**: the entry's aggregate, field by field, and the key's scope in its context and tenant (or
 *   across tenants for a system key, [ReservationScope.of]), as the kernel reserves.
 * - **Idempotent**: confirming a key its owner holds confirmed changes nothing, and releasing one it no longer holds
 *   does nothing, so a replay is harmless; a release frees only a key no later entry confirmed, so replaying an
 *   old rename (`A→B`, then `B→A`) never frees the key the later entry confirmed again (it may hold the old key back
 *   until the later entry is replayed and releases it); neither ever touches another owner's key, so an old owner running beside
 *   the new one never takes a key from the owner that holds it now. A confirmation gets a free key back
 *   for the committed fact; a key another owner took meanwhile (after the pending reservation expired) is lost to
 *   it, which is logged as an error and passed over: parking cannot get it back.
 * - **Expiry**: a pending reservation that no entry carries (the attempt crashed before appending) is
 *   never confirmed; it expires in the store, and the next reservation of the key takes it over.
 */
@InternalWowApi
class ReservationSubscription(
    private val context: String,
    private val store: ReservationStore,
    /** Told of each key a committed fact lost ([ObservedEvent.ReservationLost]). */
    private val observer: Observer = Observer.NONE,
) {
    /** The subscription to register: named [Hop.RESERVATION_SUBSCRIPTION], of every aggregate type of the context. */
    val subscription: Subscription = Subscription.builtIn(
        Hop.RESERVATION_SUBSCRIPTION,
        SubscriptionFilter(context),
        FunctionKind.RESERVATION,
        ::apply,
    )

    private suspend fun apply(entry: SubscribedEntry) {
        val transport = entry.transport ?: return
        val owner = entry.ref
        for (key in transport.reservations) {
            val scope = ReservationScope.of(owner, key)
            val outcome = store.confirm(scope, key.key, owner, entry.version)
            if (outcome is ConfirmOutcome.Taken) {
                logger.error {
                    "${entry.ref}@${entry.version} reserved ${key.key} in $scope, which ${outcome.by.owner} took " +
                        "after its pending reservation expired: the committed fact lost its key."
                }
                observer.observed(ObservedEvent.ReservationLost(context))
            }
        }
        for (key in transport.releases) {
            store.release(ReservationScope.of(owner, key), key.key, owner, entry.version)
        }
    }
}

private val logger = KotlinLogging.logger {}
