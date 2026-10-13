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

package me.ahoo.wow.runtime.timer

import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.log.ScheduleChange
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.Timers

/**
 * The built-in `timers` subscription of one bounded [context] (decision D166): writes the timers the context's
 * aggregates schedule into its [store], from the schedule changes their streams commit
 * ([me.ahoo.wow.spi.log.EventStreamRecord.schedules]). The store is a view of the log: this subscription rebuilds it
 * from the beginning of the log under a new name, and a replay writes nothing twice.
 *
 * Register [subscription] in the context's [me.ahoo.wow.runtime.subscription.SubscriptionRuntime]: it runs on each
 * partition's owner, checkpoints and parks like any subscription, and takes the streams that only change schedules.
 *
 * - **Put**: the timer `ref#key@version`, of the partition of its target among the log's [partitions], due when the
 *   decision said ([Timers.of]); it replaces what the aggregate scheduled under the key before.
 * - **Cancel**: cancels the aggregate's PENDING (or parked) timer of the key.
 * - **Delete**: a stream that deletes the aggregate cancels every PENDING timer the aggregate keeps, before its own
 *   changes apply; a restore does not bring them back.
 * - **Idempotent**: every write carries the stream's version, and the store ignores what it already holds at that
 *   version or a later one, so a replay or an old partition owner running beside the new one (decision D45) never moves
 *   a timer back nor revives one.
 */
class TimersSubscription(context: String, private val store: TimerStore, private val partitions: Int) {
    init {
        require(partitions >= 1) { "A log has at least one partition." }
    }

    /** The subscription to register: named [Hop.TIMERS_SUBSCRIPTION], of every aggregate type of the context. */
    val subscription: Subscription = Subscription.builtIn(
        Hop.TIMERS_SUBSCRIPTION,
        SubscriptionFilter(context),
        FunctionKind.TIMERS,
        ::write,
    )

    private suspend fun write(entry: SubscribedEntry) {
        val stream = entry.stream
        val owner = Timers.ownerOf(stream.ref)
        val version = stream.version.toLong()
        if (stream.events.any { it.name == Lifecycle.AGGREGATE_DELETED }) {
            store.cancelAll(owner, version)
        }
        stream.schedules.forEach { change ->
            when (change) {
                is ScheduleChange.Put -> store.put(Timers.of(stream, change, partitions))
                is ScheduleChange.Cancel -> store.cancel(owner, change.key, version)
            }
        }
    }
}
