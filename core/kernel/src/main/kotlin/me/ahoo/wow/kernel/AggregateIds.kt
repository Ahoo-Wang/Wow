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

/**
 * The one rule of which aggregate ids no route or store keeps as they were given, so that every path an id enters by
 * (admission of a caller's or a peer's request, the intake, a saga's derived and a timer's commands, an id a generator
 * gives, the test DSL) and `migrate-data`, which reports the 9.x aggregates that have one before the cut-over, never
 * disagree about an id. An aggregate exists only with an id each of its by-id routes reaches, behind the gateway in
 * front of the service too (#6594).
 */
@InternalWowApi
object AggregateIds {
    /** The longest aggregate id admission takes, in characters. */
    const val MAX_LENGTH: Int = 256

    /**
     * The longest aggregate id admission takes percent-encoded as UTF-8 ([encodedLength]): what a gateway's path check
     * counts of a path segment. CoSec 8 refuses a segment longer than this.
     */
    const val MAX_ENCODED_LENGTH: Int = 256

    /** The characters a gateway's path check refuses in a path segment, encoded: `/`, `;` and `\`. */
    const val UNCARRIED: String = "/;\\"

    /**
     * Why [id], an aggregate id, is one admission refuses whatever its length in characters, as text, or null when it
     * is not:
     *
     * - `.` or `..`, a relative path segment, which a proxy normalizing the path would send as another request;
     * - white space at either end, another aggregate than the one without it, to a reader;
     * - `\u0000` or another control character (`\u0000` to `\u001f`, `\u007f` to `\u009f`), which not every store
     *   keeps;
     * - `/`, `;` or `\` ([UNCARRIED]), which a gateway's path check refuses encoded in a path (CoSec 8 answers
     *   `%2F`, `%3B` and `%5C` with 400), so no by-id route would reach it;
     * - one longer than [MAX_ENCODED_LENGTH] characters percent-encoded as UTF-8 ([encodedLength]), which the same
     *   check refuses as too long a segment.
     *
     * A blank id is refused where it is read, and an id longer than admission takes ([MAX_LENGTH]) is refused by its
     * length ([refused]): neither is answered here.
     */
    fun unaddressable(id: String?): String? {
        if (id == null || id.isBlank()) return null
        val uncarried = id.firstOrNull { it in UNCARRIED }
        return when {
            id == "." || id == ".." -> "The aggregate id '$id' is a relative path segment, which a proxy may resolve away."
            id.first().isWhitespace() || id.last().isWhitespace() ->
                "The aggregate id '$id' begins or ends with white space, which a reader would not see."

            id.any(Char::isISOControl) ->
                "The aggregate id holds a control character (\\u0000 to \\u001f, \\u007f to \\u009f), which not every " +
                    "store keeps."

            uncarried != null ->
                "The aggregate id '$id' holds '$uncarried', which a gateway's path check refuses in a path, so no route " +
                    "by its id would reach it: an aggregate id holds no '/', ';' or '\\'."

            encodedLength(id) > MAX_ENCODED_LENGTH ->
                "The aggregate id is ${encodedLength(id)} characters percent-encoded as UTF-8, which a gateway's path " +
                    "check refuses past $MAX_ENCODED_LENGTH: a shorter id, or one of letters, digits, '-', '.', '_' " +
                    "and '~', fits."

            else -> null
        }
    }

    /**
     * Why admission refuses [id] as an aggregate id, as text, or null when it takes it: longer than [MAX_LENGTH]
     * characters, or [unaddressable]. A blank id is refused where it is read.
     */
    fun refused(id: String?): String? = id?.takeIf { it.length > MAX_LENGTH }
        ?.let { "The aggregate id is at most $MAX_LENGTH characters; this one has ${it.length}." }
        ?: unaddressable(id)

    /**
     * The length of [id] percent-encoded as UTF-8, as a client that encodes every character but the unreserved ones
     * (RFC 3986: letters, digits, `-`, `.`, `_` and `~`) writes it in a path: one character for each unreserved
     * character, three for each other byte of its UTF-8 form. A lone surrogate counts as the three bytes of the
     * replacement character an encoder writes for it.
     */
    fun encodedLength(id: String): Int {
        var length = 0
        var index = 0
        while (index < id.length) {
            val code = id.codePointAt(index)
            length += when {
                isUnreserved(code) -> 1
                code < 0x80 -> ESCAPED
                code < 0x800 -> 2 * ESCAPED
                code < 0x10000 -> 3 * ESCAPED
                else -> 4 * ESCAPED
            }
            index += Character.charCount(code)
        }
        return length
    }

    private fun isUnreserved(code: Int): Boolean =
        code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code || code in '0'.code..'9'.code ||
            code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code

    /** The characters of one percent-encoded byte: `%` and two hex digits. */
    private const val ESCAPED = 3
}
