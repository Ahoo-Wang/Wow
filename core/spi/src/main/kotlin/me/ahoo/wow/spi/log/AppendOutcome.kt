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
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * How an append ended. Every expected result is a value; only a defect is an exception.
 */
sealed interface AppendOutcome {
    /**
     * The stream is committed at [position].
     *
     * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
     */
    class Committed @InternalWowApi constructor(val position: LogPosition) : AppendOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Committed && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Committed(position=$position)"

        private fun valueFields(): List<Any?> = listOf(position)

        companion object {
            /** A Committed of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(position: LogPosition): Committed = Committed(position)
        }
    }

    /**
     * A stream with the same tenant and request id is already committed: [existing].
     *
     * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
     */
    class AlreadyCommitted @InternalWowApi constructor(val existing: EventStreamRecord) : AppendOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is AlreadyCommitted && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "AlreadyCommitted(existing=$existing)"

        private fun valueFields(): List<Any?> = listOf(existing)

        companion object {
            /** An AlreadyCommitted of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(existing: EventStreamRecord): AlreadyCommitted = AlreadyCommitted(existing)
        }
    }

    /**
     * Another stream already holds the version; the aggregate is at [currentVersion].
     *
     * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
     */
    class Conflict @InternalWowApi constructor(val currentVersion: Int) : AppendOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Conflict && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Conflict(currentVersion=$currentVersion)"

        private fun valueFields(): List<Any?> = listOf(currentVersion)

        companion object {
            /** A Conflict of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(currentVersion: Int): Conflict = Conflict(currentVersion)
        }
    }

    /**
     * Whether the append committed is unknown, for example after a timeout. The version slot decides it: read the
     * stream at the appended version before deciding again.
     *
     * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
     */
    class Unknown @InternalWowApi constructor(val cause: Throwable) : AppendOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Unknown && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Unknown(cause=$cause)"

        private fun valueFields(): List<Any?> = listOf(cause)

        companion object {
            /** An Unknown of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(cause: Throwable): Unknown = Unknown(cause)
        }
    }

    /**
     * The backend refuses the stream as it is, for good, and nothing was committed: a value it cannot store (a decimal
     * MongoDB cannot keep exactly, a `\u0000` PostgreSQL's `jsonb` does not take), an identifier too long for its
     * index. Appending it again is refused the same way, so it is neither retried nor read back; [cause] says why. In
     * a group ([EventLog.appendAll]) it fails that stream alone (findings A2-5, A2-7).
     *
     * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
     */
    class Rejected @InternalWowApi constructor(val cause: Throwable) : AppendOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Rejected && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Rejected(cause=$cause)"

        private fun valueFields(): List<Any?> = listOf(cause)

        companion object {
            /** A Rejected of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(cause: Throwable): Rejected = Rejected(cause)
        }
    }
}

/**
 * The outcome of a stream that came after [failed], of the same aggregate, in one [EventLog.appendAll], when [failed]
 * did not commit ([outcome]): not appended, a conflict at the version [outcome] leaves the aggregate at, the
 * conflict's own when [outcome] is one, else the version before [failed]'s.
 */
fun notAppendedAfter(failed: EventStreamRecord, outcome: AppendOutcome): AppendOutcome.Conflict =
    outcome as? AppendOutcome.Conflict ?: AppendOutcome.Conflict.of(failed.version - 1)

/**
 * Checks what [EventLog.appendAll] takes of one aggregate: its streams in consecutive versions, in order. A backend
 * that commits a group in one write relies on it to commit them as a prefix.
 */
fun requireChains(streams: List<Pair<EventStreamRecord, Transport?>>) {
    val last = HashMap<AggregateRef, Int>()
    streams.forEach { (stream) ->
        last.put(stream.ref, stream.version)?.let { previous ->
            require(stream.version == previous + 1) {
                "${stream.ref}'s streams in a group come in consecutive versions: ${stream.version} after $previous."
            }
        }
    }
}
