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

internal enum class JsonTokenShape {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
}

internal data class ScalarMapping(
    val tokenShape: JsonTokenShape,
    val clickHouseType: ClickHouseType.Scalar,
)

internal object ClickHouseTypeMapping {
    private val scalarMappings: Map<Class<*>, ScalarMapping> = mapOf(
        String::class.java to string(ClickHouseType.String),
        Int::class.java to integer(ClickHouseType.Int32),
        Int::class.javaObjectType to integer(ClickHouseType.Int32),
        Long::class.java to integer(ClickHouseType.Int64),
        Long::class.javaObjectType to integer(ClickHouseType.Int64),
        Float::class.java to number(ClickHouseType.Float32),
        Float::class.javaObjectType to number(ClickHouseType.Float32),
        Double::class.java to number(ClickHouseType.Float64),
        Double::class.javaObjectType to number(ClickHouseType.Float64),
        Boolean::class.java to boolean(ClickHouseType.Bool),
        Boolean::class.javaObjectType to boolean(ClickHouseType.Bool),
        Short::class.java to integer(ClickHouseType.Int16),
        Short::class.javaObjectType to integer(ClickHouseType.Int16),
        Char::class.java to string(ClickHouseType.String),
        Char::class.javaObjectType to string(ClickHouseType.String),
        Byte::class.java to integer(ClickHouseType.Int8),
        Byte::class.javaObjectType to integer(ClickHouseType.Int8),
        UUID::class.java to string(ClickHouseType.UUID),
        Duration::class.java to string(ClickHouseType.String),
        Date::class.java to string(ClickHouseType.String),
        java.sql.Date::class.java to string(ClickHouseType.String),
        LocalDate::class.java to string(ClickHouseType.String),
        LocalDateTime::class.java to string(ClickHouseType.String),
        LocalTime::class.java to string(ClickHouseType.String),
        Instant::class.java to string(ClickHouseType.String),
        ZonedDateTime::class.java to string(ClickHouseType.String),
        OffsetDateTime::class.java to string(ClickHouseType.String),
        OffsetTime::class.java to string(ClickHouseType.String),
        YearMonth::class.java to string(ClickHouseType.String),
        MonthDay::class.java to string(ClickHouseType.String),
        Period::class.java to string(ClickHouseType.String),
        Year::class.java to integer(ClickHouseType.Int32),
    )

    internal fun Class<*>.scalarMapping(): ScalarMapping? =
        scalarMappings[this] ?: takeIf(Class<*>::isEnum)?.let {
            string(ClickHouseType.String)
        }

    private fun string(type: ClickHouseType.Scalar): ScalarMapping =
        ScalarMapping(JsonTokenShape.STRING, type)

    private fun integer(type: ClickHouseType.Scalar): ScalarMapping =
        ScalarMapping(JsonTokenShape.INTEGER, type)

    private fun number(type: ClickHouseType.Scalar): ScalarMapping =
        ScalarMapping(JsonTokenShape.NUMBER, type)

    private fun boolean(type: ClickHouseType.Scalar): ScalarMapping =
        ScalarMapping(JsonTokenShape.BOOLEAN, type)
}
