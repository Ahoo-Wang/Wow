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
import org.junit.jupiter.api.assertThrows

class MixTest {
    @Test
    fun `a mix should send each scenario in its share, interleaved`() {
        val mix = Mix.parse("E1:50, E4-state:40, E4-list:10")
        mix.mixed.assert().isTrue()
        mix.label.assert().isEqualTo("E1:50,E4-state:40,E4-list:10")
        val picks = (0L until 1000L).map(mix::pick)
        picks.count { it == Scenario.E1 }.assert().isEqualTo(500)
        picks.count { it == Scenario.E4_STATE }.assert().isEqualTo(400)
        picks.count { it == Scenario.E4_LIST }.assert().isEqualTo(100)
        // Smooth: the cycle of 10 (weights 5, 4, 1) never sends more than two of one scenario in a row.
        picks.windowed(3).none { it.toSet().size == 1 }.assert().isTrue()
    }

    @Test
    fun `one scenario should be a mix of one`() {
        val mix = Mix.of(Scenario.E3)
        mix.mixed.assert().isFalse()
        mix.transfers.assert().isTrue()
        mix.label.assert().isEqualTo("E3")
        mix.pick(7).assert().isEqualTo(Scenario.E3)
        Mix.parse("E2").pick(0).assert().isEqualTo(Scenario.E2)
    }

    @Test
    fun `a mix should refuse unknown scenarios, repeats and zero weights`() {
        assertThrows<IllegalArgumentException> { Mix.parse("E9:1") }
        assertThrows<IllegalArgumentException> { Mix.parse("E1:1,E1:2") }
        assertThrows<IllegalArgumentException> { Mix.parse("E1:0") }
    }
}
