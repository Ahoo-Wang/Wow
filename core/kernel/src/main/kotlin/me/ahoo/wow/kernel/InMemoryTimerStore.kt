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

package me.ahoo.wow.kernel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore

/** A [TimerStore] in memory, for tests and samples. Every operation is serialized by one lock. */
class InMemoryTimerStore : TimerStore {
    private val lock = Mutex()
    private val timers = HashMap<Pair<String, String>, Timer>()

    override suspend fun put(timer: Timer): Boolean = lock.withLock {
        val key = timer.owner to timer.key
        val held = timers[key]
        if (held != null && (held.id == timer.id || held.version > timer.version)) {
            return false
        }
        timers[key] = timer.copy(status = TimerStatus.PENDING, attempts = 0, outcome = null)
        true
    }

    override suspend fun cancel(owner: String, key: String, version: Long): Boolean = lock.withLock {
        val held = timers[owner to key]?.takeIf { it.cancellable(version) } ?: return false
        timers[owner to key] = held.cancelled(version)
        true
    }

    override suspend fun cancelAll(owner: String, version: Long): Int = lock.withLock {
        val cancelled = timers.filter { (key, timer) -> key.first == owner && timer.cancellable(version) }
        cancelled.forEach { (key, timer) -> timers[key] = timer.cancelled(version) }
        cancelled.size
    }

    override suspend fun find(owner: String, key: String): Timer? = lock.withLock { timers[owner to key] }

    override suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer> {
        require(limit >= 1) { "A page holds at least one timer." }
        return lock.withLock {
            timers.values
                .filter { it.partition in partitions && it.status in DUE_STATUSES && it.dueAt <= until }
                .sortedWith(DUE_ORDER)
                .take(limit)
        }
    }

    override suspend fun settle(changes: List<TimerChange>): Int = lock.withLock {
        changes.count { (loaded, next) ->
            val key = loaded.owner to loaded.key
            val held = timers[key]?.takeIf { it.isAsLoaded(loaded) } ?: return@count false
            timers[key] = held.copy(
                status = next.status,
                slot = next.slot,
                slotDueAt = next.slotDueAt,
                dueAt = next.dueAt,
                attempts = next.attempts,
                outcome = next.outcome,
            )
            true
        }
    }

    override suspend fun parked(target: AggregateRef): List<Timer> = lock.withLock {
        timers.values.filter { it.target == target && it.status == TimerStatus.PARKED }.sortedWith(DUE_ORDER)
    }

    private companion object {
        val DUE_ORDER: Comparator<Timer> = compareBy<Timer>({ it.dueAt }, { it.id })
        val DUE_STATUSES = setOf(TimerStatus.PENDING, TimerStatus.FIRING)
        val CANCELLABLE = setOf(TimerStatus.PENDING, TimerStatus.FIRING, TimerStatus.PARKED)

        fun Timer.cancellable(version: Long): Boolean =
            status in CANCELLABLE && this.version <= version

        fun Timer.cancelled(version: Long): Timer = copy(status = TimerStatus.CANCELLED, version = version)

        /** Whether this, the timer held, is still [loaded] as it was loaded. */
        fun Timer.isAsLoaded(loaded: Timer): Boolean =
            id == loaded.id && status == loaded.status && slot == loaded.slot && slotDueAt == loaded.slotDueAt &&
                attempts == loaded.attempts && dueAt == loaded.dueAt
    }
}
