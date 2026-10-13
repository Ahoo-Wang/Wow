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

package me.ahoo.wow.spi.timer

import me.ahoo.wow.api.aggregate.AggregateRef

/**
 * The timers of one bounded context's scheduled commands (decision D166): a view derived from the log, rebuilt by
 * replaying it, never the source of truth. The built-in `timers` subscription writes the timers aggregates schedule
 * from their event streams; a saga writes its own before its checkpoint passes the commit. The runtime's timer runner
 * of each partition reads the timers [due] in it and sends their commands.
 *
 * - **Key**: a timer is unique by its owner and key ([Timer.owner], [Timer.key]); its [Timer.id] changes with each
 *   reschedule, so a write of the same timer again is recognised, and a mark of an old one does nothing.
 * - **Order**: each change carries the owner's [Timer.version]. A change older than what the store holds for the key
 *   does nothing, so a replay, or an old partition owner running beside the new one (decision D45), never moves a
 *   timer back nor revives one that fired or was cancelled.
 * - **Conditional updates**: [update] changes a timer only while it is still the same timer ([Timer.id]) in the status
 *   the caller read; a cancel or a reschedule that won the race leaves it as it is.
 */
interface TimerStore {
    /**
     * Writes [timer], PENDING, unless the store holds its owner's key at a later version, or holds this very timer
     * ([Timer.id]) in whatever status: writing a timer again is idempotent and never revives it.
     *
     * @return true when the store changed.
     */
    suspend fun put(timer: Timer): Boolean

    /**
     * Cancels the timer [owner] keeps under [key] when it is PENDING and the store holds it at [version] or earlier;
     * nothing otherwise.
     *
     * @return true when a timer was cancelled.
     */
    suspend fun cancel(owner: String, key: String, version: Long): Boolean

    /**
     * Cancels every PENDING timer of [owner] held at [version] or earlier: the owner aggregate was deleted.
     *
     * @return how many timers were cancelled.
     */
    suspend fun cancelAll(owner: String, version: Long): Int

    /** The timer [owner] keeps under [key], in whatever status, or null. */
    suspend fun find(owner: String, key: String): Timer?

    /**
     * Up to [limit] PENDING timers of [partition] due at or before [until] (epoch milliseconds), earliest first; ties
     * by id.
     */
    suspend fun due(partition: Int, until: Long, limit: Int): List<Timer>

    /**
     * Replaces the status, [Timer.attempts], [Timer.outcome] and [Timer.dueAt] of the timer [timer] names with
     * [timer]'s, when the store still holds that timer ([Timer.id]) in status [expected].
     *
     * @return true when it was updated.
     */
    suspend fun update(timer: Timer, expected: TimerStatus): Boolean

    /** The PARKED timers whose target is [target], earliest due first. */
    suspend fun parked(target: AggregateRef): List<Timer>
}
