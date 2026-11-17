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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryViolation

/**
 * A literal match that ignores letter case as the core's reference does (#6392). The reference compares the lower case
 * of both texts (`String.lowercase()`: Unicode's lowercase mapping, in the root locale, so no language's own rules).
 * Elasticsearch's `case_insensitive` folds ASCII letters only, so `Ä` never meets `ä`; the match is a `regexp`
 * instead, each letter of the operand a class of every character whose lower case is that letter's: `äR` is
 * `[äÄ][rR]`, and `k` is `[kK]` with the Kelvin sign (U+212A), which lowers to `k`.
 *
 * The lower case of a whole text differs from its characters' lower cases in two contexts only, which a class of
 * single characters does not follow: `İ` (U+0130) lowers to `i̇`, two characters, and a capital sigma that ends a
 * word lowers to `ς`, where the character alone lowers to `σ`.
 */
internal object ElasticsearchCaseFolding {
    /** Each lower case that a character other than itself has, as the class of its characters, itself first. */
    private val classes: Map<Int, String> by lazy {
        val members = HashMap<Int, StringBuilder>()
        for (codePoint in 0..Character.MAX_CODE_POINT) {
            val lower = Character.toLowerCase(codePoint)
            if (lower != codePoint) {
                members.getOrPut(lower) { StringBuilder().appendCodePoint(lower) }.appendCodePoint(codePoint)
            }
        }
        members.mapValues { (_, characters) -> "[$characters]" }
    }

    /**
     * The Lucene regular expression that matches a text of [field] holding [operand] ignoring case: after anything
     * when [leading], before anything when [trailing]. Every ASCII character but a letter or a digit is escaped, an
     * operator or not; a letter or a digit never is, since `\d` and `\w` are classes.
     *
     * A pattern longer than [MAX_PATTERN_LENGTH] is refused here, before any request, as the storage's limit
     * ([QueryViolation.StorageUnsupported]), where Elasticsearch would answer with its own error.
     */
    fun pattern(field: QueryField<*>, operand: String, leading: Boolean, trailing: Boolean): String {
        val pattern = pattern(operand, leading, trailing)
        if (pattern.length > MAX_PATTERN_LENGTH) {
            throw QueryViolation.StorageUnsupported(
                "an ignore-case match of a ${operand.length}-character operand, whose pattern of ${pattern.length} " +
                    "characters passes the $MAX_PATTERN_LENGTH an Elasticsearch regexp holds",
                field,
            ).rejection()
        }
        return pattern
    }

    private fun pattern(operand: String, leading: Boolean, trailing: Boolean): String = buildString {
        if (leading) append(ANY)
        operand.codePoints().forEach { codePoint ->
            val characters = classes[Character.toLowerCase(codePoint)]
            when {
                characters != null -> append(characters)
                codePoint < ASCII_END && !Character.isLetterOrDigit(codePoint) ->
                    append('\\').appendCodePoint(codePoint)
                else -> appendCodePoint(codePoint)
            }
        }
        if (trailing) append(ANY)
    }

    /** The flags of the `regexp`: no optional operator, so only the classes and [ANY] above are operators. */
    const val FLAGS = "NONE"

    /**
     * The longest pattern sent: Elasticsearch's default `index.max_regex_length`. Most letters take four
     * characters (`[aA]`), so an operand of about 250 letters reaches it. The bound is fixed: an index whose setting
     * is raised does not raise it, since a query is compiled without reading its index's settings.
     */
    const val MAX_PATTERN_LENGTH = 1000

    private const val ANY = ".*"
    private const val ASCII_END = 0x80
}
