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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class PascalCaseStrategyTest {

    companion object {
        const val PHRASE = "WowIsGreat"
        val WORDS = listOf("Wow", "Is", "Great")
    }

    @Test
    fun segment() {
        val actual = PascalCaseStrategy.segment(PHRASE)
        actual.assert().isEqualTo(WORDS)
    }

    @Test
    fun transform() {
        val actual = PascalCaseStrategy.transform(WORDS)
        actual.assert().isEqualTo(PHRASE)
    }
}
