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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit
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
    fun `the zipf choosers of a sweep's clients should share one table`() {
        KeyChooser.sharedZipfCumulative(1000, 1.1).assert().isSameAs(KeyChooser.sharedZipfCumulative(1000, 1.1))
        KeyChooser.sharedZipfCumulative(1000, 1.1).assert().isNotSameAs(KeyChooser.sharedZipfCumulative(1000, 1.2))
    }

    @Test
    fun `a transfer's target should be another account`() {
        val chooser = KeyChooser(KeyChooser.HOT, 3, seed = 1, hotFraction = 0.9)
        repeat(1000) { (chooser.nextOtherThan(0) != 0).assert().isTrue() }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `single should send every request to the first account and a transfer's target among the others`() {
        val chooser = KeyChooser(KeyChooser.SINGLE, 10, seed = 1)
        List(1000) { chooser.next() }.distinct().assert().containsExactly(0)
        val targets = IntArray(10)
        repeat(draws) { targets[chooser.nextOtherThan(0)]++ }
        targets[0].assert().isZero()
        (1 until 10).forEach { abs(targets[it].toDouble() / draws - 1.0 / 9).assert().isLessThan(0.01) }
        chooser.describe().assert().isEqualTo(mapOf("distribution" to "single", "accounts" to 10))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a hot share of all requests to one account should draw a transfer's target among the others`() {
        val chooser = KeyChooser(KeyChooser.HOT, 10, seed = 1, hotFraction = 1.0, hotKeys = 1)
        List(100) { chooser.nextOtherThan(0) }.forEach { it.assert().isBetween(1, 9) }
    }

    @Test
    fun `a hot fraction outside 0 to 1 should be refused`() {
        assertThrows<IllegalArgumentException> { KeyChooser(KeyChooser.HOT, 10, seed = 1, hotFraction = 1.5) }
        assertThrows<IllegalArgumentException> { KeyChooser(KeyChooser.HOT, 10, seed = 1, hotFraction = -0.1) }
    }
}
