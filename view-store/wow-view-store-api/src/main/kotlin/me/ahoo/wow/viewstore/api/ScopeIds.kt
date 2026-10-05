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

package me.ahoo.wow.viewstore.api

/**
 * The rule for a tenant or owner id, which the starter checks on every path and the domain on a claimed owner: an id
 * is what it displays as. It is not empty, and has no character that shows as nothing or as a blank:
 * - whitespace (Unicode spaces included), control and format characters (such as U+200B, U+FEFF, U+00AD, U+2060,
 *   U+180E), surrogates, private-use and unassigned code points;
 * - and [INVISIBLE], invisible characters of other categories: the combining grapheme joiner, the variation
 *   selectors, the Mongolian free variation selectors, the Khmer inherent vowels, the Hangul fillers and the braille
 *   blank.
 *
 * Without this rule `owner/alice%E2%80%8B` would be an owner that reads as `alice`, and `owner/%20` a blank one (Wow
 * before 9.3.0 read a blank one as missing). An id is a user or tenant id, not a display name, so the joiners (ZWJ U+200D, ZWNJ U+200C,
 * format characters) are refused too, although some scripts and emoji sequences use them in text.
 *
 * Unassigned is decided by the running JDK's Unicode version (`Character.getType`): a later JDK assigns more code
 * points, so upgrading it only accepts more ids, and an id valid before stays valid. A code point assigned after the
 * JDK's version is refused until the JDK knows it.
 */
object ScopeIds {
    /** Invisible code points outside the refused categories, as inclusive ranges. */
    private val INVISIBLE: List<IntRange> = listOf(
        0x034F..0x034F, // combining grapheme joiner (Mn)
        0x115F..0x1160, // Hangul choseong and jungseong fillers (Lo)
        0x17B4..0x17B5, // Khmer inherent vowels (Mn)
        0x180B..0x180F, // Mongolian free variation selectors (Mn; U+180E is a format character)
        0x2800..0x2800, // braille pattern blank (So)
        0x3164..0x3164, // Hangul filler (Lo)
        0xFE00..0xFE0F, // variation selectors (Mn)
        0xFFA0..0xFFA0, // halfwidth Hangul filler (Lo)
        0xE0100..0xE01EF, // variation selectors supplement (Mn)
    )

    fun isValid(id: String?): Boolean =
        !id.isNullOrEmpty() && id.codePoints().noneMatch { it.isInvisible() }

    private fun Int.isInvisible(): Boolean {
        if (Character.isWhitespace(this) || Character.isSpaceChar(this) || Character.isISOControl(this)) {
            return true
        }
        if (INVISIBLE.any { this in it }) {
            return true
        }
        return when (Character.getType(this).toByte()) {
            Character.FORMAT,
            Character.SURROGATE,
            Character.PRIVATE_USE,
            Character.UNASSIGNED,
            -> true

            else -> false
        }
    }
}
