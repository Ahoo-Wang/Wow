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

package me.ahoo.wow.benchmark.load

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.pow

class KeyChooserTest {
    private val draws = 200_000

    private fun shares(chooser: KeyChooser): DoubleArray {
        val counts = IntArray(chooser.accounts)
        repeat(draws) { counts[chooser.next()]++ }
        return DoubleArray(counts.size) { counts[it].toDouble() / draws }
    }

    @Test
    fun `uniform should spread the keys evenly`() {
        val shares = shares(KeyChooser(KeyChooser.UNIFORM, 10, seed = 1))
        shares.forEach { abs(it - 0.1).assert().isLessThan(0.01) }
    }

    @Test
    fun `zipf should draw rank k in proportion to 1 over k to the s`() {
        val shares = shares(KeyChooser(KeyChooser.ZIPF, 100, seed = 1))
        val norm = (1..100).sumOf { 1.0 / it.toDouble().pow(1.1) }
        listOf(0, 1, 9).forEach { index ->
            val expected = 1.0 / (index + 1).toDouble().pow(1.1) / norm
            abs(shares[index] - expected).assert().isLessThan(0.01)
        }
        KeyChooser.zipfCumulative(100, 1.1).last().assert().isEqualTo(1.0)
    }

    @Test
    fun `hot should send its share to the hot keys`() {
        val shares = shares(KeyChooser(KeyChooser.HOT, 100, seed = 1, hotFraction = 0.5, hotKeys = 1))
        abs(shares[0] - (0.5 + 0.5 / 100)).assert().isLessThan(0.01)
    }

    @Test
    fun `the same seed should draw the same keys`() {
        val first = KeyChooser(KeyChooser.ZIPF, 1000, seed = 7)
        val second = KeyChooser(KeyChooser.ZIPF, 1000, seed = 7)
        List(100) { first.next() }.assert().isEqualTo(List(100) { second.next() })
    }

    @Test
    fun `a transfer's target should be another account`() {
        val chooser = KeyChooser(KeyChooser.HOT, 3, seed = 1, hotFraction = 0.9)
        repeat(1000) { (chooser.nextOtherThan(0) != 0).assert().isTrue() }
    }
}
