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

package me.ahoo.wow.spi.subscription

/**
 * The checkpoints of subscriptions (documentation/designs/v10/04-subscriptions.md §1): one per (subscription group,
 * subscription, partition), the lowest log sequence up to which every entry of the partition is completed.
 *
 * A checkpoint only moves forward: [save] ignores a position lower than the stored one, and saving the stored one
 * again changes nothing. That is what makes a lease handover safe without fencing (decision D45): while ownership of
 * a partition moves, the old owner may still save for a short while; its positions are never ahead of what it really
 * completed, so the store keeps the highest completed position whichever owner wrote last, and a late, lower write
 * from a zombie owner is harmless. Replaying from an earlier position is not a backward save: it is a new subscription
 * (04 §3).
 */
interface CheckpointStore {
    /** The checkpoint of [subscription] in [group] for [partition], or null when it has none yet. */
    suspend fun load(group: String, subscription: String, partition: Int): Long?

    /** Moves the checkpoint to [position] if that is ahead of the stored one; otherwise does nothing. */
    suspend fun save(group: String, subscription: String, partition: Int, position: Long)
}
