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

package me.ahoo.wow.compiler.model

/**
 * A domain's name as generated code writes it (#6282): KSP gives a name without the backticks it was declared in, so a
 * name Kotlin does not read unquoted, a hard keyword (`` `when` ``) or one with a space (`` `apply event` ``), is
 * quoted again. Any other name is written as it is.
 */
internal object KotlinNames {
    /** Kotlin's hard keywords: never an identifier unquoted, even after a dot. */
    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
        "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
        "when", "while",
    )
    private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{Nd}_]*")

    /** The simple [name], in backticks unless Kotlin reads it unquoted. */
    fun quoted(name: String): String =
        if (IDENTIFIER.matches(name) && name !in KEYWORDS && name.any { it != '_' }) name else "`$name`"

    /** The qualified [name], each of its segments [quoted]. */
    fun qualified(name: String): String = name.split('.').joinToString(".") { quoted(it) }
}
