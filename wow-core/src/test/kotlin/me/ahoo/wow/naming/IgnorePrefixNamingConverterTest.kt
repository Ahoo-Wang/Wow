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

internal class IgnorePrefixNamingConverterTest {
    companion object {
        const val PREFIX = "prefix_"
        val CONVERTER = IgnorePrefixNamingConverter(PREFIX)
    }

    @Test
    fun convert() {
        assertThat(CONVERTER.prefix, equalTo(PREFIX))
        val actual = CONVERTER.convert(PREFIX)
        assertThat(actual, equalTo(""))
    }

    @Test
    fun convertWhenMismatch() {
        val phrase = "prefix"
        val actual = CONVERTER.convert(phrase)
        assertThat(actual, equalTo(phrase))
    }
}
