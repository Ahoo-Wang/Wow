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

package me.ahoo.wow.infra.batch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Maintains the global bounds shared by every batch lane.
 */
internal class BatchAdmission<T : Any>(
    capacity: Int,
) {
    private val availableItems = Semaphore(capacity)
    private val availableQueueSlots = Semaphore(capacity)
    private val pending = ConcurrentHashMap.newKeySet<BatchRequest<T>>()

    /**
     * Atomically reserves one live item and one physical queue slot from the
     * caller's perspective. The semaphores themselves remain independent
     * because their release times differ after cancellation.
     */
    fun tryAcquire(): Boolean {
        if (!availableItems.tryAcquire()) {
            return false
        }
        if (!availableQueueSlots.tryAcquire()) {
            availableItems.release()
            return false
        }
        return true
    }

    fun track(value: T): BatchRequest<T> {
        return BatchRequest(
            value = value,
            onReleaseAdmission = ::releaseAdmission,
            onReleaseQueueSlot = availableQueueSlots::release,
        ).also(pending::add)
    }

    fun releaseUntracked() {
        availableQueueSlots.release()
        availableItems.release()
    }

    fun pendingSnapshot(): List<BatchRequest<T>> = pending.toList()

    private fun releaseAdmission(request: BatchRequest<T>) {
        if (pending.remove(request)) {
            availableItems.release()
        }
    }
}
