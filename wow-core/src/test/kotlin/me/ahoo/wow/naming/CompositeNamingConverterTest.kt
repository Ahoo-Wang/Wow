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

internal class CompositeNamingConverterTest {
    companion object {
        const val PREFIX = "prefix_"
        val CONVERTER =
            CompositeNamingConverter(IgnorePrefixNamingConverterTest.CONVERTER, PascalCaseStrategy, SnakeCaseStrategy)
    }

    @Test
    fun convert() {
        val phrase = PREFIX + PascalCaseStrategyTest.PHRASE
        val actual = CONVERTER.convert(phrase)
        actual.assert().isEqualTo(SnakeCaseStrategyTest.PHRASE)
    }

    @Test
    fun convertWhenMismatch() {
        val phrase = "prefix" + PascalCaseStrategyTest.PHRASE
        val actual = CONVERTER.convert(phrase)
        actual.assert().isEqualTo("prefix_wow_is_great")
    }
}
