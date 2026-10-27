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

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.schedule.ScheduleSlots
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.Timers

/**
 * The built-in `timers` subscription of one bounded [context]: writes the timers the context's
 * aggregates schedule into its [store], from the system events `wow.ScheduleSet` and `wow.ScheduleCancelled` their
 * streams commit. The store is a view of the log: this subscription rebuilds it from the beginning of the log under a
 * new name, and a replay writes nothing twice.
 *
 * Register [subscription] in the runtime of the log group whose timers [store] keeps: it runs on each
 * partition's owner, checkpoints and parks like any subscription, and takes the streams that only change schedules.
 *
 * - **Set**: the timer `ref#key@version`, of its owner's partition among the log's [partitions], its first slot
 *   due as its rule says ([ScheduleSlots.timerOf]: a cron rule's first time after the commit); it replaces what the
 *   aggregate scheduled under the key before. A job instance's own tick (`<context>.schedule`) is sent
 *   as `system:schedule` in its tenant, without the setting command's headers and scope.
 * - **Local**: a timer this node writes is handed to [written] (the context's runner), which takes it
 *   into its firing wheel at once when it owns the timer's partition and the timer is due soon, without waiting for
 *   its next read of the store.
 * - **Cancelled**: cancels the aggregate's PENDING (or parked) timer of the key.
 * - **Delete**: a stream that deletes the aggregate cancels every PENDING timer the aggregate keeps, before its own
 *   changes apply; a restore does not bring them back.
 * - **Idempotent**: every write carries the stream's version, and the store ignores what it already holds at that
 *   version or a later one, so a replay or an old partition owner running beside the new one never moves
 *   a timer back nor revives one.
 */
@InternalWowApi
class TimersSubscription(
    context: String,
    private val store: TimerStore,
    private val partitions: Int,
    private val written: (Timer) -> Unit = {},
) {
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
        stream.events.forEach { event ->
            when (event.name) {
                Schedules.SCHEDULE_SET -> {
                    val timer = timerOf(stream, json.readValue(event.payload, ScheduleSet::class.java))
                    if (store.put(timer)) {
                        written(timer)
                    }
                }

                Schedules.SCHEDULE_CANCELLED ->
                    store.cancel(owner, json.readValue(event.payload, ScheduleCancelled::class.java).key, version)
            }
        }
    }

    private fun timerOf(stream: EventStreamRecord, set: ScheduleSet): Timer {
        val timer = ScheduleSlots.timerOf(stream, set, partitions)
        if (set.commandType != ScheduleJobs.TICK) {
            return timer
        }
        val tenantId = stream.ref.tenantId
        return timer.copyWith(
            operator = Operator.system(ScheduleJobs.COMPONENT, tenantId),
            headers = emptyMap(),
            scope = Scope.NONE,
        )
    }

    private companion object {
        val json = WowJson.mapper
    }
}
