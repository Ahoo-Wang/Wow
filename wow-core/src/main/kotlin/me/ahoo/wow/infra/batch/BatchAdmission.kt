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
    private val observations: BatchObservationEmitter?,
) {
    private val availableItems = Semaphore(capacity)
    private val availableQueueSlots = Semaphore(capacity)
    private val pending = ConcurrentHashMap.newKeySet<BatchRequest<T>>()

    /**
     * Atomically reserves one live item and one physical queue slot from the
     * caller's perspective. The semaphores themselves remain independent
     * because their release times differ after cancellation.
     */
    fun tryAcquire(): BatchAdmissionRejectionReason? {
        if (!availableItems.tryAcquire()) {
            return BatchAdmissionRejectionReason.LIVE_ITEMS_EXHAUSTED
        }
        if (!availableQueueSlots.tryAcquire()) {
            availableItems.release()
            return BatchAdmissionRejectionReason.QUEUE_SLOTS_EXHAUSTED
        }
        observations?.capacityChanged(liveDelta = 1, queueDelta = 1)
        return null
    }

    fun track(value: T): BatchRequest<T> {
        val currentObservations = observations
        val request = if (currentObservations == null) {
            BatchRequest(
                value = value,
                onReleaseAdmission = ::releaseAdmission,
                onReleaseQueueSlot = ::releaseQueueSlot,
            )
        } else {
            ObservedBatchRequest(
                value = value,
                onReleaseAdmission = ::releaseAdmission,
                onReleaseQueueSlot = ::releaseQueueSlot,
                enqueuedAtNanos = currentObservations.markEnqueued(),
                observations = currentObservations,
            )
        }
        return request.also(pending::add)
    }

    fun releaseUntracked() {
        observations?.capacityChanged(liveDelta = -1, queueDelta = -1)
        availableQueueSlots.release()
        availableItems.release()
    }

    fun pendingSnapshot(): List<BatchRequest<T>> = pending.toList()

    private fun releaseAdmission(request: BatchRequest<T>) {
        if (pending.remove(request)) {
            observations?.capacityChanged(liveDelta = -1, queueDelta = 0)
            availableItems.release()
        }
    }

    private fun releaseQueueSlot() {
        observations?.capacityChanged(liveDelta = 0, queueDelta = -1)
        availableQueueSlots.release()
    }
}
