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

package me.ahoo.wow.query.converter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FieldConverterTest {

    @Test
    fun `should convert field using identity converter`() {
        val identityConverter = FieldConverter { it }
        identityConverter.convert("field1").assert().isEqualTo("field1")
    }

    @Test
    fun `should convert field using prefix converter`() {
        val prefixConverter = FieldConverter { "state.$it" }
        prefixConverter.convert("field1").assert().isEqualTo("state.field1")
    }

    @Test
    fun `should convert field using suffix converter`() {
        val suffixConverter = FieldConverter { "${it}_suffix" }
        suffixConverter.convert("field1").assert().isEqualTo("field1_suffix")
    }
}
