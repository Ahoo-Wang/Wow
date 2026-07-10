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

package me.ahoo.wow.bi.type

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.type.ClickHouseTypeMapping.scalarMapping
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Date
import java.util.UUID

class ClickHouseTypeMappingTest {
    @Test
    fun `should map only lossless JVM scalar wire shapes`() {
        listOf(
            String::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            Int::class.java to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int32),
            Int::class.javaObjectType to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int32),
            Long::class.java to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int64),
            Long::class.javaObjectType to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int64),
            Float::class.java to ScalarMapping(JsonTokenShape.NUMBER, ClickHouseType.Float32),
            Float::class.javaObjectType to ScalarMapping(JsonTokenShape.NUMBER, ClickHouseType.Float32),
            Double::class.java to ScalarMapping(JsonTokenShape.NUMBER, ClickHouseType.Float64),
            Double::class.javaObjectType to ScalarMapping(JsonTokenShape.NUMBER, ClickHouseType.Float64),
            Boolean::class.java to ScalarMapping(JsonTokenShape.BOOLEAN, ClickHouseType.Bool),
            Boolean::class.javaObjectType to ScalarMapping(JsonTokenShape.BOOLEAN, ClickHouseType.Bool),
            Short::class.java to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int16),
            Short::class.javaObjectType to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int16),
            Char::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            Char::class.javaObjectType to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            Byte::class.java to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int8),
            Byte::class.javaObjectType to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int8),
            UUID::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.UUID),
            Duration::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            Date::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            java.sql.Date::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            LocalDate::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            LocalDateTime::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            LocalTime::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            Instant::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            ZonedDateTime::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            OffsetDateTime::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            OffsetTime::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            YearMonth::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            MonthDay::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            Period::class.java to ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String),
            Year::class.java to ScalarMapping(JsonTokenShape.INTEGER, ClickHouseType.Int32),
        ).forEach { (jvmType, expected) ->
            jvmType.scalarMapping().assert().isEqualTo(expected)
        }
    }

    @Test
    fun `should not claim a fixed scalar mapping for arbitrary precision or inline duration`() {
        BigDecimal::class.java.scalarMapping().assert().isNull()
        kotlin.time.Duration::class.java.scalarMapping().assert().isNull()
    }

    @Test
    fun `should expose an enum string candidate for wire verification`() {
        SampleEnum::class.java.scalarMapping().assert()
            .isEqualTo(ScalarMapping(JsonTokenShape.STRING, ClickHouseType.String))
    }

    @Test
    fun `should return null for unsupported JVM type`() {
        Thread::class.java.scalarMapping().assert().isNull()
    }

    private enum class SampleEnum
}
