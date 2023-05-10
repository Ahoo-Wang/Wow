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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

internal class CamelCaseStrategyTest {
    companion object {
        const val PHRASE = "wowIsGreat"
        val WORDS = listOf("wow", "Is", "Great")
    }

    @Test
    fun segment() {
        val actual = CamelCaseStrategy.segment(PHRASE)
        assertThat(actual, equalTo(WORDS))
    }

    @Test
    fun transform() {
        val actual = CamelCaseStrategy.transform(WORDS)
        assertThat(actual, equalTo(PHRASE))
    }
}
