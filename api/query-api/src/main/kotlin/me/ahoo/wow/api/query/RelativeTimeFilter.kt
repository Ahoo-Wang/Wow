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

package me.ahoo.wow.api.query

import java.time.ZoneId

private fun String?.requireTimeZone() {
    if (this != null) {
        require(isNotBlank()) { "timeZone cannot be blank." }
        zoneIdOf(this)
    }
}

/** Parses a time zone id, rejecting unknown ids with the framework's own message rather than the JDK's. */
internal fun zoneIdOf(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (error: java.time.DateTimeException) {
    throw IllegalArgumentException("Unknown time zone [$id].", error)
}

/**
 * A window of time relative to the moment a query runs, such as today or the last seven days. Its days are read in
 * [timeZone]; how the window's bounds are written is the field's: the encoding its schema declares
 * (`@QueryTemporal`), an epoch in its unit or a text in its pattern.
 */
sealed interface RelativeTimeFilter : FieldPredicate {
    /**
     * The time zone, an IANA region such as `Asia/Shanghai` or an offset such as `+08:00`; `null`, the default, is
     * UTC, never the server's own zone.
     */
    val timeZone: String?

    /** The zone [timeZone] names; `null` when the filter names none. */
    fun resolvedTimeZone(): ZoneId? = timeZone?.let(::zoneIdOf)
}

internal fun RelativeTimeFilter.validateConfiguration() {
    timeZone.requireTimeZone()
}
