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

package me.ahoo.wow.query.backend

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** Unicode's simple case folding, the one table every backend's ignore-case match follows (#6773). */
class CaseFoldingTest {
    private fun folds(left: String, right: String): Boolean = CaseFolding.fold(left) == CaseFolding.fold(right)

    @Test
    fun `the letters a lowercase mapping leaves apart should fold together`() {
        folds("ſ", "S").assert().isTrue()
        folds("µ", "Μ").assert().isTrue()
        folds("ΟΔΟΣ", "οδος").assert().isTrue()
        folds("σ", "ς").assert().isTrue()
        folds("Ǆ", "ǅ").assert().isTrue()
        folds("ǅ", "ǆ").assert().isTrue()
        folds("k", "K").assert().isTrue()
        folds("ß", "ẞ").assert().isTrue()
        // A simple folding of Unicode 15.1, which no JDK's lowercase or uppercase mapping gives.
        folds("ﬅ", "ﬆ").assert().isTrue()
        folds("Ärger", "äRGER").assert().isTrue()
    }

    @Test
    fun `the Turkic I's should fold with no other letter`() {
        folds("İ", "i").assert().isFalse()
        folds("İ", "I").assert().isFalse()
        folds("ı", "i").assert().isFalse()
        folds("ı", "I").assert().isFalse()
        CaseFolding.classOf('İ'.code).assert().isNull()
        CaseFolding.classOf('ı'.code).assert().isNull()
    }

    @Test
    fun `a text should fold character by character, keeping its length in characters`() {
        CaseFolding.fold("ΟΔΟΣ").assert().isEqualTo("ΟΔΟΣ".map { CaseFolding.fold(it.toString()) }.joinToString(""))
        CaseFolding.fold("İstanbul").codePointCount(0, CaseFolding.fold("İstanbul").length).assert().isEqualTo(8)
        CaseFolding.fold("1 + 1 = 2").assert().isEqualTo("1 + 1 = 2")
        CaseFolding.fold("").assert().isEmpty()
    }

    @Test
    fun `a pattern should be the class of each letter, and ASCII punctuation escaped`() {
        CaseFolding.pattern("Σs").assert().isEqualTo("[Σςσ][Ssſ]")
        CaseFolding.pattern("İ").assert().isEqualTo("İ")
        CaseFolding.pattern("中1 \t").assert().isEqualTo("中1 \t")
        val punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        CaseFolding.pattern(punctuation).assert().isEqualTo(punctuation.map { "\\$it" }.joinToString(""))
        CaseFolding.pattern("𐐀").assert().isEqualTo("[𐐀𐐨]")
    }

    @Test
    fun `the table should be classes of at least two characters, each in one class, ascending`() {
        val seen = HashSet<Int>()
        for (codePoint in 0..Character.MAX_CODE_POINT) {
            val members = CaseFolding.classOf(codePoint) ?: continue
            members.size.assert().isGreaterThan(1)
            members.toList().assert().isSorted()
            members.forEach { CaseFolding.classOf(it).assert().isSameAs(members) }
            if (codePoint == members.first()) {
                members.forEach { seen.add(it).assert().isTrue() }
            }
        }
        seen.size.assert().isGreaterThan(MIN_FOLDED)
    }

    /**
     * Every character the running JDK defines folds as its lowercase of its uppercase, but where Unicode's simple case
     * folding differs from that: the Turkic I's, which it leaves apart, and three foldings of Unicode 15.1 that join
     * letters no case mapping joins. A table regenerated from another Unicode version that changes more fails here.
     */
    @Test
    fun `the table should be the JDK's lowercase of its uppercase but where the folding differs`() {
        val parted = setOf('İ'.code, 'ı'.code)
        val joined = listOf(0xFB05 to 0xFB06, 0x1FD3 to 0x0390, 0x1FE3 to 0x03B0)
        val defined = (0..Character.MAX_CODE_POINT).filter { Character.isDefined(it) && it !in parted }
        // The JDK's classes: the characters of one lowercase of their uppercase, with the three joins.
        val jdk = HashMap<Int, MutableSet<Int>>()
        defined.forEach { jdk.getOrPut(Character.toLowerCase(Character.toUpperCase(it))) { HashSet() }.add(it) }
        val classOf = HashMap<Int, MutableSet<Int>>()
        jdk.values.forEach { members -> members.forEach { classOf[it] = members } }
        joined.forEach { (left, right) ->
            val union = (classOf.getValue(left) + classOf.getValue(right)).toMutableSet()
            union.forEach { classOf[it] = union }
        }
        val differences = defined.filter { codePoint ->
            val table = CaseFolding.classOf(codePoint)?.filter { Character.isDefined(it) }?.toSet() ?: setOf(codePoint)
            table != classOf.getValue(codePoint)
        }
        differences.map { "U+%04X".format(it) }.assert().isEmpty()
    }

    private companion object {
        /** Fewer characters fold with others than Unicode 16's 2,900-odd only if the table lost lines. */
        const val MIN_FOLDED = 2_800
    }
}
