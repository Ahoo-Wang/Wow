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

package me.ahoo.wow.api.query.mask

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaField

class MaskingTest {
    @Test
    fun `full mask preserves Unicode code point count`() {
        val mask = FullMaskStrategy.compile(Masked::secret.javaField!!.getAnnotation(Mask::class.java))
        mask.mask("A中😀").assert().isEqualTo("***")
        mask.mask("").assert().isEmpty()
    }

    @Test
    fun `keep mask preserves edges and fully masks short values`() {
        val rule = Kept::phone.javaField!!.getAnnotation(KeepMask::class.java)
        val mask = KeepMaskStrategy.compile(rule)
        mask.mask("13800138000").assert().isEqualTo("138****8000")
        mask.mask("1234567").assert().isEqualTo("*******")
        KeepMaskStrategy.compile(HugeKeep::value.javaField!!.getAnnotation(KeepMask::class.java))
            .mask("short").assert().isEqualTo("*****")
    }

    @Test
    fun `keep mask rejects negative parameters`() {
        assertThrownBy<IllegalArgumentException> {
            KeepMaskStrategy.compile(InvalidKeep::value.javaField!!.getAnnotation(KeepMask::class.java))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private data class Masked(@field:Mask val secret: String)
    private data class Kept(@field:KeepMask(prefix = 3, suffix = 4) val phone: String)
    private data class InvalidKeep(@field:KeepMask(prefix = -1) val value: String)
    private data class HugeKeep(@field:KeepMask(prefix = Int.MAX_VALUE, suffix = 1) val value: String)
}
