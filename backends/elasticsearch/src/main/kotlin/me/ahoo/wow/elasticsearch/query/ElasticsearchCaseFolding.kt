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
import me.ahoo.wow.query.backend.CaseFolding
import me.ahoo.wow.query.schema.QueryViolation

/**
 * A literal match that ignores letter case by the core's folding ([CaseFolding]): Unicode's simple case folding,
 * the same table on every backend. Elasticsearch's `case_insensitive` folds ASCII letters only, so `Ä` never meets
 * `ä`; the match is a `regexp` instead, the core's [CaseFolding.pattern] of the operand, each letter the class of
 * every character that folds with it: `äR` is `[Ää][Rr]`, and `k` is `[Kk]` with the Kelvin sign (U+212A).
 */
internal object ElasticsearchCaseFolding {
    /**
     * The Lucene regular expression that matches a text of [field] holding [operand] ignoring case: after anything
     * when [leading], before anything when [trailing].
     *
     * A pattern longer than [MAX_PATTERN_LENGTH] is refused here, before any request, as the storage's limit
     * ([QueryViolation.StorageUnsupported]), where Elasticsearch would answer with its own error.
     */
    fun pattern(field: QueryField<*>, operand: String, leading: Boolean, trailing: Boolean): String {
        val pattern = (if (leading) ANY else "") + CaseFolding.pattern(operand) + (if (trailing) ANY else "")
        if (pattern.length > MAX_PATTERN_LENGTH) {
            throw QueryViolation.StorageUnsupported(
                "an ignore-case match of a ${operand.length}-character operand, whose pattern of ${pattern.length} " +
                    "characters passes the $MAX_PATTERN_LENGTH an Elasticsearch regexp holds",
                field,
            ).rejection()
        }
        return pattern
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
}
