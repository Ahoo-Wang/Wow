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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** An operand matched ignoring case is the core's pattern of it ([me.ahoo.wow.query.backend.CaseFolding]), anchored. */
class ElasticsearchCaseFoldingTest {
    private fun pattern(operand: String, leading: Boolean = false, trailing: Boolean = false) =
        ElasticsearchCaseFolding.pattern(FIELD, operand, leading, trailing)

    @Test
    fun `each letter should be the class of the characters it folds with, outside ASCII too`() {
        pattern("äR").assert().isEqualTo("[Ää][Rr]")
        // The Kelvin sign folds with k, and a sigma with its two other forms wherever it stands.
        pattern("k").assert().isEqualTo("[Kk\u212A]")
        pattern("Σς").assert().isEqualTo("[Σςσ][Σςσ]")
        // A character outside the Basic Multilingual Plane: Deseret.
        pattern("\uD801\uDC00").assert().isEqualTo("[\uD801\uDC00\uD801\uDC28]")
        // `İ` folds with no other character.
        pattern("İ").assert().isEqualTo("İ")
    }

    @Test
    fun `ASCII punctuation should be escaped, and anything else kept, around the anchors asked`() {
        pattern("中1 .*\\").assert().isEqualTo("中1 \\.\\*\\\\")
        pattern("a", leading = true, trailing = true).assert().isEqualTo(".*[Aa].*")
        pattern("", leading = true).assert().isEqualTo(".*")
    }

    @Test
    fun `a pattern past Elasticsearch's default regexp length should be refused before any request`() {
        // 249 letters of four characters and the two anchors make 1000, the bound itself.
        pattern("a".repeat(249), leading = true, trailing = true).length.assert().isEqualTo(1000)
        assertThrows<QuerySchemaValidationException> {
            pattern("a".repeat(250), leading = true, trailing = true)
        }.violation.assert().isEqualTo(
            QueryViolation.StorageUnsupported(
                "an ignore-case match of a 250-character operand, whose pattern of 1004 characters passes the " +
                    "1000 an Elasticsearch regexp holds",
                FIELD,
            ),
        )
    }

    private companion object {
        val FIELD = QueryField("state.name")
    }
}
