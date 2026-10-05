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
package me.ahoo.wow.naming

import java.util.regex.Pattern

interface Tokenizer {
    fun segment(phrase: String): List<String>
}

/**
 * NamingStrategy.
 *
 * @author ahoo wang
 */
interface NamingStrategy {
    fun transform(words: List<String>): String
}

/**
 * SnakeCaseStrategy.
 * smart_generator
 *
 * @author ahoo wang
 */
object SnakeCaseStrategy : NamingStrategy, Tokenizer {
    const val SEPARATOR = "_"
    override fun segment(phrase: String): List<String> {
        return phrase.split(SEPARATOR)
    }

    override fun transform(words: List<String>): String {
        return words.joinToString(separator = SEPARATOR) {
            it.lowercase()
        }
    }
}

object LowerDotCaseStrategy : NamingStrategy, Tokenizer {
    const val SEPARATOR = "."
    override fun segment(phrase: String): List<String> {
        return phrase.split(SEPARATOR)
    }

    override fun transform(words: List<String>): String {
        return words.joinToString(separator = SEPARATOR) {
            it.lowercase()
        }
    }
}

object KebabCaseStrategy : NamingStrategy, Tokenizer {
    const val SEPARATOR = "-"
    override fun segment(phrase: String): List<String> {
        return phrase.split(SEPARATOR)
    }

    override fun transform(words: List<String>): String {
        return words.joinToString(separator = SEPARATOR) {
            it.lowercase()
        }
    }
}

val UPPERCASE_SEPARATOR_PATTERN: Pattern = Pattern.compile("(?=[A-Z])")

object PascalCaseStrategy : NamingStrategy, Tokenizer {
    override fun segment(phrase: String): List<String> {
        return phrase.split(UPPERCASE_SEPARATOR_PATTERN)
    }

    override fun transform(words: List<String>): String = buildString {
        for (word in words) {
            val firstChar = word.substring(0, 1).uppercase()
            append(firstChar)
            val leftChar = word.substring(1).lowercase()
            append(leftChar)
        }
    }
}

object CamelCaseStrategy : NamingStrategy, Tokenizer {
    override fun segment(phrase: String): List<String> {
        return phrase.split(UPPERCASE_SEPARATOR_PATTERN)
    }

    override fun transform(words: List<String>): String = buildString {
        var isFirstWord = true
        for (word in words) {
            var firstChar = word.substring(0, 1).uppercase()
            if (isFirstWord) {
                firstChar = firstChar.lowercase()
                isFirstWord = false
            }
            append(firstChar)
            val leftChar = word.substring(1).lowercase()
            append(leftChar)
        }
    }
}
