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
import java.time.Instant

/**
 * A command a schedule sent ([me.ahoo.wow.api.decide.Command.schedule]) reached the kernel of [ref]
 * longer before its slot's [due] time, by this kernel's clock ([now]), than the kernel waits for one: the firing
 * node's clock runs that far ahead of this one. The command is not decided and the aggregate's queue is not held for
 * it; the firing runner sends it again later, under the same request id, as after any technical failure.
 */
@InternalWowApi
class ScheduleNotDueException(
    val ref: AggregateRef,
    val requestId: String,
    val due: Long,
    val now: Long,
) : RuntimeException(
    "The scheduled command $requestId on $ref is due at ${Instant.ofEpochMilli(due)}, ${due - now} ms after this " +
        "node's clock (${Instant.ofEpochMilli(now)}): the firing node's clock is ahead, so it is sent again later.",
)
