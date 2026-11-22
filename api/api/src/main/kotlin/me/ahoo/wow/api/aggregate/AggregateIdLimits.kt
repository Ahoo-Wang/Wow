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

package me.ahoo.wow.api.aggregate

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * How long an aggregate id may be, so that every route by its id reaches it behind a gateway's path check: the one
 * definition, which the kernel's rule of ids (`AggregateIds`) and the check of the routes a Manifest declares both
 * read, and the documentation states. A gateway that bounds a path differently changes these numbers here alone.
 *
 * - [MAX_LENGTH], 256 UTF-16 units: the longest path segment the gateway takes, decoded.
 * - [MAX_BYTES], 512 bytes in UTF-8: the id's share of the longest path the gateway takes, [MAX_PATH_LENGTH] less
 *   what the rest of a route by id may take ([MAX_ROUTE_REST]), 1,536 characters, over the most a percent-encoder
 *   writes for one byte ([MAX_ESCAPE]): whatever a client encodes, the id's segment fits its share.
 *
 * The gateway is CoSec 8 (`PathNormalizer`): a segment of at most 256 UTF-16 units once decoded, and a path of at most
 * 2,048 characters as received, without its query.
 */
@InternalWowApi
object AggregateIdLimits {
    /** The longest aggregate id, in UTF-16 units: the longest path segment the gateway takes, decoded. */
    const val MAX_LENGTH: Int = 256

    /** The longest path the gateway takes, in characters as received, percent-encoded, without its query. */
    const val MAX_PATH_LENGTH: Int = 2048

    /**
     * The most a route by id may take of [MAX_PATH_LENGTH] besides its id, in characters percent-encoded: its context,
     * its aggregate, its last segments and its separators. A Manifest whose route by id takes more is refused.
     */
    const val MAX_ROUTE_REST: Int = 512

    /** The most characters a percent-encoder writes for one byte: `%` and two hex digits. */
    const val MAX_ESCAPE: Int = 3

    /** The most bytes an aggregate id holds in UTF-8: its share of the path, 1,536 characters, over [MAX_ESCAPE]. */
    const val MAX_BYTES: Int = (MAX_PATH_LENGTH - MAX_ROUTE_REST) / MAX_ESCAPE

    /**
     * The number of bytes of [text] in UTF-8, without encoding it. A lone surrogate counts three bytes, the most an
     * encoder writes for it (the replacement character; the JDK's writes `?`, one).
     */
    @JvmStatic
    fun utf8Length(text: String): Int {
        var length = 0
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            length += bytesOf(code)
            index += Character.charCount(code)
        }
        return length
    }

    /**
     * The length of [path] percent-encoded as a client writes it, its `/` separators kept: one character for each
     * `/` and each unreserved character (RFC 3986: letters, digits, `-`, `.`, `_` and `~`), three for each other
     * byte of its UTF-8 form.
     */
    @JvmStatic
    fun encodedLength(path: String): Int {
        var length = 0
        var index = 0
        while (index < path.length) {
            val code = path.codePointAt(index)
            length += if (code == '/'.code || isUnreserved(code)) 1 else MAX_ESCAPE * bytesOf(code)
            index += Character.charCount(code)
        }
        return length
    }

    /** The bytes of the code point [code] in UTF-8; a lone surrogate's the replacement character's three. */
    private fun bytesOf(code: Int): Int = when {
        code < 0x80 -> 1
        code < 0x800 -> 2
        code < 0x10000 -> 3
        else -> 4
    }

    private fun isUnreserved(code: Int): Boolean =
        code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code || code in '0'.code..'9'.code ||
            code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
}
