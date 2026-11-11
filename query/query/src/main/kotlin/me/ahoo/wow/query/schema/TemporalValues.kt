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

package me.ahoo.wow.query.schema

import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.TimeUnit

/*
 * The one reading of an epoch's number as an instant and back: exact in both directions, with no intermediate unit
 * narrower than the instant (a count of nanoseconds in a Long ends in 2262).
 */

private const val NANOS_PER_SECOND = 1_000_000_000L

/** A time unit's word in the capability descriptor and the Manifest: its name in lower case, `milliseconds`. */
internal val TimeUnit.word: String
    get() = name.lowercase()

/** The time unit [word] names: only the lower-case word, `milliseconds`, names one. */
internal fun timeUnitOf(word: String): TimeUnit = requireNotNull(TimeUnit.entries.firstOrNull { it.word == word }) {
    "Unknown time unit [$word]: one of ${TimeUnit.entries.joinToString { it.word }}."
}

/** An epoch of [TimeUnit.MILLISECONDS], the unit a number that declares none counts in. */
internal val EPOCH_MILLIS: Temporal.Epoch = Temporal.Epoch(TimeUnit.MILLISECONDS)

/**
 * The instant [value], a count of this epoch's unit since 1970-01-01T00:00:00Z, stands for, exactly; `null` when it
 * lies beyond the instants Java represents.
 */
internal fun Temporal.Epoch.instantOf(value: Long): Instant? = try {
    val perSecond = timeUnit.convert(1, TimeUnit.SECONDS)
    if (perSecond == 0L) {
        // A unit longer than a second: a whole number of seconds.
        Instant.ofEpochSecond(Math.multiplyExact(value, timeUnit.toSeconds(1)))
    } else {
        Instant.ofEpochSecond(
            Math.floorDiv(value, perSecond),
            Math.floorMod(value, perSecond) * (NANOS_PER_SECOND / perSecond),
        )
    }
} catch (_: ArithmeticException) {
    null
} catch (_: DateTimeException) {
    null
}

/** [instant] as a count of this epoch's unit, floored, exactly; `null` when that count is past a Long. */
internal fun Temporal.Epoch.epochOf(instant: Instant): Long? = try {
    val perSecond = timeUnit.convert(1, TimeUnit.SECONDS)
    if (perSecond == 0L) {
        Math.floorDiv(instant.epochSecond, timeUnit.toSeconds(1))
    } else {
        Math.addExact(Math.multiplyExact(instant.epochSecond, perSecond), instant.nano / (NANOS_PER_SECOND / perSecond))
    }
} catch (_: ArithmeticException) {
    null
}

/** This instant in epoch milliseconds, floored; `null` past the milliseconds a Long holds. */
internal fun Instant.epochMilliOrNull(): Long? = try {
    toEpochMilli()
} catch (_: ArithmeticException) {
    null
}
