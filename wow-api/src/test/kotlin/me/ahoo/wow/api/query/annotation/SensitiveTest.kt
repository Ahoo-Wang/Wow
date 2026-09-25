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

package me.ahoo.wow.api.query.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

class SensitiveTest {
    @Test
    fun `default mask masks every Unicode code point`() {
        val mask = KeepMaskStrategy()
        mask.mask("A中😀").assert().isEqualTo("***")
        mask.mask("").assert().isEmpty()
    }

    @Test
    fun `keep mask preserves edges and fully masks short values`() {
        KeepMaskStrategy(3, 4).mask("13800138000").assert().isEqualTo("138****8000")
        KeepMaskStrategy(3, 4).mask("1234567").assert().isEqualTo("*******")
        KeepMaskStrategy(Int.MAX_VALUE, 1).mask("short").assert().isEqualTo("*****")
        KeepMaskStrategy(2, 2).mask("A中😀BCD").assert().isEqualTo("A中**CD")
    }

    @Test
    fun `keep mask rejects negative parameters`() {
        assertThrownBy<IllegalArgumentException> { KeepMaskStrategy(keepPrefix = -1) }
    }

    @Test
    fun `sensitive annotation is retained with its level and mask`() {
        val confidential = Fixture::password.javaField!!.getAnnotation(Sensitive::class.java)
        confidential.level.assert().isEqualTo(SensitivityLevel.CONFIDENTIAL)
        confidential.mask.assert().isEqualTo(Mask())
        confidential.mask.strategy.assert().isEqualTo(MaskStrategy::class)

        val display = Fixture::class.java.getDeclaredMethod("getPhone").getAnnotation(Sensitive::class.java)
        display.level.assert().isEqualTo(SensitivityLevel.DISPLAY)
        display.mask.assert().isEqualTo(Mask(keepPrefix = 3, keepSuffix = 4))
    }

    @Test
    fun `temporal annotation is retained on fields and getters`() {
        Fixture::createdAt.javaField!!.getAnnotation(QueryTemporal::class.java).assert()
            .isEqualTo(QueryTemporal())
        Fixture::class.java.getDeclaredMethod("getUpdatedAt").getAnnotation(QueryTemporal::class.java).unit
            .assert().isEqualTo(TimeUnit.SECONDS)
        Fixture::placedOn.javaField!!.getAnnotation(QueryTemporal::class.java).pattern
            .assert().isEqualTo("yyyy-MM-dd")
    }

    private data class Fixture(
        @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val password: String,
        @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 4))
        val phone: String,
        @field:QueryTemporal
        val createdAt: Long,
        @get:QueryTemporal(unit = TimeUnit.SECONDS)
        val updatedAt: Long,
        @field:QueryTemporal(pattern = "yyyy-MM-dd")
        val placedOn: String,
    )
}
