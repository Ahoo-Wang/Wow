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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.spi.subscription.Checkpoint
import me.ahoo.wow.spi.subscription.CheckpointStore

/**
 * Where a [SubscriptionWorker] stores its [watermark], the checkpoint of its [subscription] in [partition]:
 * in the runtime's saves of its workers, periodic or on a stop ([unsaved], then [saved]). It remembers the last
 * position stored, starting at the [checkpoint] the worker started from, so it never stores one twice nor a lower one.
 */
internal class CheckpointSaves(
    private val subscription: Subscription,
    private val partition: Int,
    checkpoint: Long,
    /** The worker's watermark now. */
    private val watermark: () -> Long,
) {
    private val lock = Mutex()

    /** The last position stored. Written under `lock`. */
    @Volatile
    private var saved = checkpoint

    /**
     * The watermark when it moved since the last save, else null: what the runtime's saves store, periodic or on a
     * stop, several workers at once ([CheckpointStore.saveAll]), then report with [saved].
     */
    fun unsaved(): Checkpoint? {
        val position = watermark()
        return if (position > saved) Checkpoint.of(subscription.name, partition, position) else null
    }

    /** Records that [checkpoint], from [unsaved], is stored. */
    suspend fun saved(checkpoint: Checkpoint) = lock.withLock {
        if (checkpoint.position > saved) {
            saved = checkpoint.position
        }
    }
}
