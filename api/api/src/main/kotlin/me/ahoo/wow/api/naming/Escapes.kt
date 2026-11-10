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
package me.ahoo.wow.api.naming

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The one escaping rule of Wow's identity texts (decision D408): a name joined into a text with separators has each
 * separator it holds, and `%` itself, percent-escaped as `%` and two upper-case hex digits, so the text splits back one
 * way only. A name that holds none of them is the text as it is, so an ordinary name reads the same in every text.
 * [AggregateRef][me.ahoo.wow.api.aggregate.AggregateRef]'s and [ExecutionId][me.ahoo.wow.api.subscribe.ExecutionId]'s
 * texts, the ledger's ops ids and the timers' ids use it.
 */
@InternalWowApi
object Escapes {
    private const val ESCAPE = '%'
    private const val HEX = "0123456789ABCDEF"

    /**
     * [text] with each character of [reserved] escaped (`%2F` for `/`); [reserved] holds `%` and ASCII characters only.
     * One scan, and [text] itself, not a copy, when it holds none of them.
     */
    fun escaped(text: String, reserved: String): String {
        val first = text.indexOfFirst { it in reserved }
        if (first < 0) {
            return text
        }
        return buildString(text.length + 2) {
            append(text, 0, first)
            for (index in first until text.length) {
                val char = text[index]
                if (char in reserved) {
                    append(ESCAPE).append(HEX[char.code shr 4]).append(HEX[char.code and 0xF])
                } else {
                    append(char)
                }
            }
        }
    }

    /**
     * The text [escaped] made [text] from: each `%XX` decoded; null when a `%` is not followed by two upper-case hex
     * digits.
     */
    fun unescaped(text: String): String? {
        if (ESCAPE !in text) {
            return text
        }
        val decoded = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != ESCAPE) {
                decoded.append(char)
                index++
                continue
            }
            val high = HEX.indexOf(text.getOrNull(index + 1) ?: return null)
            val low = HEX.indexOf(text.getOrNull(index + 2) ?: return null)
            if (high < 0 || low < 0) {
                return null
            }
            decoded.append(((high shl 4) or low).toChar())
            index += 3
        }
        return decoded.toString()
    }
}
