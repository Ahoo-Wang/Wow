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

package me.ahoo.wow.spi.store

import me.ahoo.wow.api.annotation.WowSpi

/**
 * Snapshot writes a later durable write to the same store covers: a facet of a [Store] ([Store.facet]) whose
 * snapshots can then be written without waiting for their own durability.
 *
 * The node [cover]s a context when its log, the checkpoints its snapshot subscription saves, and every store its
 * snapshots go to are this one store. The subscription saves a checkpoint only once the snapshots it covers are written,
 * so on one database a checkpoint durable after a crash covers snapshots durable with it, and a snapshot the crash lost
 * comes after the last durable checkpoint: the subscription writes it again from the log. A context split over stores
 * is never covered, since there a checkpoint durable in one store says nothing of the snapshots another lost. A store
 * without the facet writes every snapshot durably, as one not covered does.
 */
@WowSpi
fun interface CoveredSnapshots {
    /** [context]'s snapshot writes on this store are covered by its checkpoints there. */
    fun cover(context: String)
}
