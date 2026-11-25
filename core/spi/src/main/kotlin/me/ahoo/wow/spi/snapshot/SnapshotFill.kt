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

package me.ahoo.wow.spi.snapshot

import me.ahoo.wow.api.annotation.WowSpi

/**
 * Who fills a snapshot table from the log's beginning ([SnapshotTable.fill]): decided once, by the claim that first
 * records the table ([SnapshotRebuildStore.claim]), from whether the store's snapshot subscription had a checkpoint
 * then, and read the same by every node.
 *
 * No case is added within 10.x, so a `when` over it may name every case.
 */
@WowSpi
enum class SnapshotFill {
    /** The store's own snapshot subscription, with no checkpoint at the first claim: it starts at the beginning. */
    SUBSCRIPTION,

    /** A backfill subscription of the table's own: the store's subscription was already past the beginning. */
    BACKFILL,
}
