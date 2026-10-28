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

package me.ahoo.wow.http

import java.security.MessageDigest
import java.util.HexFormat

/**
 * The entity tags of an aggregate's state, and the conditional headers that name them (RFC 9110 §8.8.3, §13.1.1,
 * §13.1.2), in one grammar for the state read that writes them and the command and the read that take them back:
 *
 * - A state read is tagged `"<version>-<digest>"` ([state]), the digest the first 64 bits of the SHA-256 of the body
 *   as hex, so the tag changes with whatever changes what the caller reads, not only with the version.
 * - A header is `*` or a comma-separated list of entity tags, each `"<opaque>"` or weak `W/"<opaque>"` ([parse]).
 *   Anything else does not parse.
 * - `If-Match` compares strongly ([versions]): a weak tag never matches, and a strong one names the aggregate's
 *   version as a state read tags it (`"3-…"`) or alone (`"3"`). Any other tag names no version, so matches none.
 * - `If-None-Match` compares weakly ([matchesWeakly]): `W/` is ignored, and `*` matches any tag.
 */
internal object EntityTags {
    /** The hex digits of the body digest a state's tag carries: 64 bits. */
    private const val DIGEST_HEX_LENGTH = 16

    private val VERSION_TAG = Regex("(0|[1-9][0-9]{0,9})(-[0-9a-f]{$DIGEST_HEX_LENGTH})?")

    /** The entity tag of a state at [version] read as [body]: `"<version>-<digest>"`. */
    fun state(version: Int, body: ByteArray): String {
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
        return "\"$version-${digest.take(DIGEST_HEX_LENGTH)}\""
    }

    /** One entity tag of a header: its [opaque] tag, quotes included, and whether it is [weak]. */
    data class Tag(val opaque: String, val weak: Boolean)

    /** What a conditional header names: any current representation (`*`), or the [Listed] tags. */
    sealed interface Condition {
        /** `*`. */
        data object Any : Condition

        /** A list of one tag or more. */
        data class Listed(val tags: List<Tag>) : Condition
    }

    /** The condition of a header's [value], or null when it does not parse. */
    fun parse(value: String): Condition? {
        val trimmed = value.trim()
        if (trimmed == "*") return Condition.Any
        val tags = ArrayList<Tag>()
        var i = 0
        while (i < trimmed.length) {
            i = trimmed.skipSpaces(i)
            if (i < trimmed.length && trimmed[i] == ',') {
                i++
                continue
            }
            val weak = trimmed.startsWith("W/", i)
            if (weak) i += 2
            if (i >= trimmed.length || trimmed[i] != '"') return null
            val end = trimmed.indexOf('"', i + 1)
            if (end < 0) return null
            val opaque = trimmed.substring(i, end + 1)
            if (!opaque.substring(1, opaque.length - 1).all(::isTagChar)) return null
            tags += Tag(opaque, weak)
            i = trimmed.skipSpaces(end + 1)
            if (i < trimmed.length && trimmed[i] != ',') return null
        }
        return if (tags.isEmpty()) null else Condition.Listed(tags)
    }

    /** The versions the strong [tags] name; empty when none of them names one, which matches no version. */
    fun versions(tags: List<Tag>): Set<Int> = tags.filterNot { it.weak }
        .mapNotNull { VERSION_TAG.matchEntire(it.opaque.removeSurrounding("\""))?.groupValues?.get(1)?.toIntOrNull() }
        .toSet()

    /** Whether [condition] names [tag] by the weak comparison of `If-None-Match`. */
    fun matchesWeakly(condition: Condition, tag: String): Boolean = when (condition) {
        Condition.Any -> true
        is Condition.Listed -> condition.tags.any { it.opaque == tag }
    }

    /** `etagc`: `!`, `#` to `~`, and obs-text; no space, no `"`. */
    private fun isTagChar(char: Char): Boolean = char == '!' || char in '#'..'~' || char.code in 0x80..0xFF

    private fun String.skipSpaces(from: Int): Int {
        var i = from
        while (i < length && (this[i] == ' ' || this[i] == '\t')) i++
        return i
    }
}
