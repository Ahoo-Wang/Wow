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
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private fun String?.requireZoneId() {
    if (this != null) {
        require(isNotBlank()) { "zoneId cannot be blank." }
        zoneIdOf(this)
    }
}

private fun String?.toDateFormatter(): DateTimeFormatter? {
    if (this == null) return null
    require(isNotBlank()) { "datePattern cannot be blank." }
    return dateFormatterOf(this)
}

/** Parses a time zone id, rejecting unknown ids with the framework's own message rather than the JDK's. */
internal fun zoneIdOf(id: String): ZoneId = ZONES.parse(id) {
    try {
        ZoneId.of(id)
    } catch (error: java.time.DateTimeException) {
        throw IllegalArgumentException("Unknown time zone [$id].", error)
    }
}

/** Parses a date-time pattern, rejecting invalid patterns with the framework's own message rather than the JDK's. */
internal fun dateFormatterOf(pattern: String): DateTimeFormatter = PATTERNS.parse(pattern) {
    try {
        DateTimeFormatter.ofPattern(pattern)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("datePattern [$pattern] is not a valid date-time pattern.", error)
    }
}

/*

 * Zones and formatters are immutable and every relative-time node of every query parses one, so each is parsed once.
 * Ids and patterns come from callers: only valid ones are kept, the MAX_PARSED most recently used of each, so no
 * run of distinct keys can pin the cache and leave later ones parsed per use.
 */
private const val MAX_PARSED = 256

private val ZONES = ParsedCache<ZoneId>()

private val PATTERNS = ParsedCache<DateTimeFormatter>()

/** A bounded, least-recently-used cache of parsed values; a key whose parse throws is never kept. */
private class ParsedCache<V : Any> {
    private val entries = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > MAX_PARSED
    }

    fun parse(key: String, parse: () -> V): V {
        synchronized(entries) { entries[key] }?.let { return it }
        val parsed = parse()
        synchronized(entries) { entries.putIfAbsent(key, parsed) }
        return parsed
    }
}

sealed interface RelativeTimeFilter : FieldPredicate {
    val zoneId: String?
    val datePattern: String?

    /** A formatter given in process instead of [datePattern]; never written on the wire. */
    val dateFormatter: DateTimeFormatter?

    /** Unit used for numeric time fields; ignored when a date formatter is configured. */
    val timeUnit: TimeUnit

    fun resolvedDateFormatter(): DateTimeFormatter? = dateFormatter ?: datePattern.toDateFormatter()

    /** The zone [zoneId] names, parsed once per id; `null` when the filter names none. */
    fun resolvedZoneId(): ZoneId? = zoneId?.let(::zoneIdOf)
}

internal fun RelativeTimeFilter.validateConfiguration() {
    zoneId.requireZoneId()
    datePattern.toDateFormatter()
}
