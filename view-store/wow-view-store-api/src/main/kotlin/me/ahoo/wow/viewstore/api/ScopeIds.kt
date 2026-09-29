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
 * is what it displays as. It is not empty, and has no character that shows as nothing or as a blank: whitespace
 * (Unicode spaces included), control and format characters (such as U+200B, U+FEFF, U+00AD, U+2060, U+180E),
 * surrogates, private-use and unassigned code points. Without this rule `owner/alice%E2%80%8B` would be an owner
 * that reads as `alice`, and `owner/%20` a blank one that Wow reads as missing.
 */
object ScopeIds {
    fun isValid(id: String?): Boolean =
        !id.isNullOrEmpty() && id.codePoints().noneMatch { it.isInvisible() }

    private fun Int.isInvisible(): Boolean {
        if (Character.isWhitespace(this) || Character.isSpaceChar(this) || Character.isISOControl(this)) {
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
