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

internal object ClickHouseTypeMapping {
    private val scalarTypes: Map<Class<*>, ClickHouseType.Scalar> = mapOf(
        String::class.java to ClickHouseType.String,
        Int::class.java to ClickHouseType.Int32,
        Int::class.javaObjectType to ClickHouseType.Int32,
        Long::class.java to ClickHouseType.Int64,
        Long::class.javaObjectType to ClickHouseType.Int64,
        Float::class.java to ClickHouseType.Float32,
        Float::class.javaObjectType to ClickHouseType.Float32,
        Double::class.java to ClickHouseType.Float64,
        Double::class.javaObjectType to ClickHouseType.Float64,
        Boolean::class.java to ClickHouseType.Bool,
        Boolean::class.javaObjectType to ClickHouseType.Bool,
        Short::class.java to ClickHouseType.Int16,
        Short::class.javaObjectType to ClickHouseType.Int16,
        Char::class.java to ClickHouseType.String,
        Char::class.javaObjectType to ClickHouseType.String,
        Byte::class.java to ClickHouseType.Int8,
        Byte::class.javaObjectType to ClickHouseType.Int8,
        BigDecimal::class.java to ClickHouseType.Decimal(38, 18),
        UUID::class.java to ClickHouseType.UUID,
        Duration::class.java to ClickHouseType.Decimal64(9),
        kotlin.time.Duration::class.java to ClickHouseType.UInt64,
        Date::class.java to ClickHouseType.UInt64,
        java.sql.Date::class.java to ClickHouseType.UInt64,
        LocalDate::class.java to ClickHouseType.String,
        LocalDateTime::class.java to ClickHouseType.String,
        LocalTime::class.java to ClickHouseType.String,
        Instant::class.java to ClickHouseType.Decimal64(9),
        ZonedDateTime::class.java to ClickHouseType.String,
        OffsetDateTime::class.java to ClickHouseType.String,
        OffsetTime::class.java to ClickHouseType.String,
        YearMonth::class.java to ClickHouseType.String,
        MonthDay::class.java to ClickHouseType.String,
        Period::class.java to ClickHouseType.String,
        Year::class.java to ClickHouseType.UInt32
    )

    internal fun Class<*>.isClickHouseScalar(): Boolean = scalarTypes.containsKey(this) || isEnum

    internal fun Class<*>.toClickHouseScalar(): ClickHouseType.Scalar {
        scalarTypes[this]?.let {
            return it
        }
        require(isEnum) { "Unsupported ClickHouse scalar type: $name" }
        return ClickHouseType.String
    }
}
