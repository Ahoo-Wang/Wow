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
import org.junit.jupiter.api.Test

/** Each letter of an operand matched ignoring case is the class of every character with its lower case (#6392). */
class ElasticsearchCaseFoldingTest {
    private fun pattern(operand: String) = ElasticsearchCaseFolding.pattern(operand, leading = false, trailing = false)

    @Test
    fun `a letter should be the class of every character whose lower case it has, outside ASCII too`() {
        pattern("äR").assert().isEqualTo("[äÄ][rR]")
        // The Kelvin sign lowers to k, the Angstrom sign to å, the capital sharp s to ß.
        pattern("k").assert().isEqualTo("[kKK]")
        pattern("Å").assert().isEqualTo("[åÅÅ]")
        pattern("ß").assert().isEqualTo("[ßẞ]")
        // A character outside the Basic Multilingual Plane: Deseret.
        pattern("𐐀").assert().isEqualTo("[𐐨𐐀]")
        // A final sigma is its own lower case, which no other character has.
        pattern("Σς").assert().isEqualTo("[σΣ]ς")
    }

    @Test
    fun `ASCII but a letter or a digit should be escaped, and anything else kept, around the anchors asked`() {
        pattern("中1 .*\\").assert().isEqualTo("中1\\ \\.\\*\\\\")
        ElasticsearchCaseFolding.pattern("a", leading = true, trailing = true).assert().isEqualTo(".*[aA].*")
        ElasticsearchCaseFolding.pattern("", leading = true, trailing = false).assert().isEqualTo(".*")
    }
}
