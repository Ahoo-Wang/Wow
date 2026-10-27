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

package me.ahoo.wow.test

import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec

/**
 * [outcome] as a failing check names it, with the events decoded as the model's classes: `committed
 * [ItemAdded(…)] at v3`, `unchanged at v2`, `rejected <code>: <message>`.
 */
internal fun describe(outcome: CommandOutcome, model: AggregateModel<*>, codec: EventCodec): String = when (outcome) {
    is CommandOutcome.Committed -> {
        val events = codec.decodeEvents(model, outcome.stream.events)
        val deleted = if (outcome.deleted) ", deleting the aggregate" else ""
        "committed $events at v${outcome.stream.version}$deleted"
    }
    is CommandOutcome.AlreadyCommitted -> "a retry of the request committed at v${outcome.stream.version}"
    is CommandOutcome.Unchanged -> "unchanged at v${outcome.version}"
    is CommandOutcome.Rejected -> "rejected ${outcome.rejection.code}: ${outcome.rejection.message}"
    is CommandOutcome.Contended -> "contended after ${outcome.attempts} attempts"
    is CommandOutcome.Unknown -> "an unknown outcome: ${outcome.cause}"
}
