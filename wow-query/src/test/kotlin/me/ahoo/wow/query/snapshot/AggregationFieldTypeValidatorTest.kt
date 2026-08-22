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

package me.ahoo.wow.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.MonthDay
import java.time.OffsetDateTime

class AggregationFieldTypeValidatorTest {
    @Test
    fun `should recognize only scalar terms types`() {
        String::class.java.toJavaType().isAggregationScalar.assert().isTrue()
        Int::class.java.toJavaType().isAggregationScalar.assert().isTrue()
        TestEnum::class.java.toJavaType().isAggregationScalar.assert().isTrue()
        TestPojo::class.java.toJavaType().isAggregationScalar.assert().isFalse()
    }

    @Test
    fun `should reject partial temporal date histogram types`() {
        Instant::class.java.toJavaType().isAggregationDate.assert().isTrue()
        LocalDate::class.java.toJavaType().isAggregationDate.assert().isTrue()
        OffsetDateTime::class.java.toJavaType().isAggregationDate.assert().isTrue()
        Month::class.java.toJavaType().isAggregationDate.assert().isFalse()
        MonthDay::class.java.toJavaType().isAggregationDate.assert().isFalse()
        LocalTime::class.java.toJavaType().isAggregationDate.assert().isFalse()
    }

    private fun Class<*>.toJavaType() = JsonSerializer.typeFactory.constructType(this)

    private enum class TestEnum {
        VALUE,
    }

    private data class TestPojo(val value: String)
}
