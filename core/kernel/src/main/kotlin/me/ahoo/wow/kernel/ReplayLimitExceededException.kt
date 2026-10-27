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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A read-only state read of [ref] ([Kernel.stateAt], [Kernel.trace]) would replay more than [limit] streams of its log:
 * it is refused before the stream past [limit] is folded, so a read of a long history costs at
 * most [limit] streams and never holds the history in memory. The state the read asked for exists; it is only too far
 * from the snapshot, or from version 1, to be replayed for a point read.
 */
@InternalWowApi
class ReplayLimitExceededException(val ref: AggregateRef, val limit: Int) :
    RuntimeException(
        "Reading this state of $ref replays more than $limit streams of its log: snapshots of its type keep a read " +
            "within that.",
    )
