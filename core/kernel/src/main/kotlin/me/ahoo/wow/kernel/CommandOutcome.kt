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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition

/** How the kernel finished a command; the runtime reads it, an application never does. */
@InternalWowApi
sealed interface CommandOutcome {
    /**
     * The command's events are committed as [stream] at [position]; [state] is the state after them. [results] are
     * what its decide function put for the caller ([me.ahoo.wow.api.decide.DecideScope.putResult]); they are not
     * kept, so a duplicate of the command is answered without them. [deleted], [tags] and [schedules] are the metadata the kernel
     * keeps beside the state after the stream ([CachedState]); its scope is the [stream]'s.
     */
    @InternalWowApi
    class Committed(
        val stream: EventStreamRecord,
        val position: LogPosition,
        val state: Any,
        val results: Map<String, Any> = emptyMap(),
        val deleted: Boolean = false,
        val tags: Map<String, List<String>> = emptyMap(),
        val schedules: Map<String, ScheduleRule> = emptyMap(),
        /** What the aggregate's first stream recorded, when the kernel knows it (work package B.O-4). */
        val first: FirstCommit? = null,
    ) : CommandOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Committed && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String =
            "Committed(stream=$stream, position=$position, state=$state, results=$results, deleted=$deleted, " +
                "tags=$tags, schedules=$schedules, first=$first)"

        private fun valueFields(): List<Any?> =
            listOf(stream, position, state, results, deleted, tags, schedules, first)
    }

    /** The same command was already committed as [stream]: it was not decided again. */
    @InternalWowApi
    class AlreadyCommitted(val stream: EventStreamRecord) : CommandOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is AlreadyCommitted && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "AlreadyCommitted(stream=$stream)"

        private fun valueFields(): List<Any?> = listOf(stream)
    }

    /** The command was accepted and changed nothing; the aggregate stays at [version]. */
    @InternalWowApi
    class Unchanged(val version: Int) : CommandOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Unchanged && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Unchanged(version=$version)"

        private fun valueFields(): List<Any?> = listOf(version)
    }

    /** The command was refused; nothing was written. */
    @InternalWowApi
    class Rejected(val rejection: Rejection) : CommandOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Rejected && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Rejected(rejection=$rejection)"

        private fun valueFields(): List<Any?> = listOf(rejection)
    }

    /** Concurrent writers kept winning the version slot for [attempts] attempts. */
    @InternalWowApi
    class Contended(val attempts: Int) : CommandOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Contended && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Contended(attempts=$attempts)"

        private fun valueFields(): List<Any?> = listOf(attempts)
    }

    /** Whether the command committed could not be established. */
    @InternalWowApi
    class Unknown(val cause: Throwable) : CommandOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Unknown && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Unknown(cause=$cause)"

        private fun valueFields(): List<Any?> = listOf(cause)
    }
}
