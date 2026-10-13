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

package me.ahoo.wow.spi.log

import me.ahoo.wow.api.aggregate.AggregateRef

/**
 * A change to one of the aggregate's scheduled commands, under its [key] (decision D166): what
 * `DecideScope.schedule` and `DecideScope.cancelSchedule` record in the event stream they commit with
 * ([EventStreamRecord.schedules]). The timer store is derived from these changes and can be rebuilt from the log.
 */
sealed interface ScheduleChange {
    /** The schedule's name within the aggregate. */
    val key: String

    /**
     * Schedules the command of type [commandType] (`context.aggregate.Command`), [body] its JSON, to be sent to
     * [target] at [dueAt] (epoch milliseconds); replaces what the aggregate scheduled under [key] before.
     */
    data class Put(
        override val key: String,
        val dueAt: Long,
        val target: AggregateRef,
        val commandType: String,
        val body: String,
    ) : ScheduleChange {
        init {
            require(key.isNotBlank()) { "A schedule needs a key." }
            require(commandType.isNotBlank()) { "A schedule names the type of its command." }
        }
    }

    /** Cancels what the aggregate scheduled under [key], if anything. */
    data class Cancel(override val key: String) : ScheduleChange {
        init {
            require(key.isNotBlank()) { "A schedule needs a key." }
        }
    }
}
