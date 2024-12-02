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

package me.ahoo.wow.bi.expansion

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
import java.util.*

private val SQL_TYPE_MAPPING: MutableMap<Class<*>, String> = mutableMapOf()

object SqlTypeMapping : MutableMap<Class<*>, String> by SQL_TYPE_MAPPING {

    init {
        SQL_TYPE_MAPPING[String::class.java] = "String"
        SQL_TYPE_MAPPING[Int::class.java] = "Int32"
        SQL_TYPE_MAPPING[Integer::class.java] = "Int32"
        SQL_TYPE_MAPPING[Long::class.java] = "Int64"
        SQL_TYPE_MAPPING[java.lang.Long::class.java] = "Int64"
        SQL_TYPE_MAPPING[Float::class.java] = "Float32"
        SQL_TYPE_MAPPING[java.lang.Float::class.java] = "Float32"
        SQL_TYPE_MAPPING[Double::class.java] = "Float64"
        SQL_TYPE_MAPPING[java.lang.Double::class.java] = "Float64"
        SQL_TYPE_MAPPING[Boolean::class.java] = "Bool"
        SQL_TYPE_MAPPING[java.lang.Boolean::class.java] = "Bool"
        SQL_TYPE_MAPPING[Short::class.java] = "UInt16"
        SQL_TYPE_MAPPING[java.lang.Short::class.java] = "UInt16"
        SQL_TYPE_MAPPING[Char::class.java] = "UInt16"
        SQL_TYPE_MAPPING[Character::class.java] = "UInt16"
        SQL_TYPE_MAPPING[Byte::class.java] = "UInt8"
        SQL_TYPE_MAPPING[java.lang.Byte::class.java] = "UInt8"
        SQL_TYPE_MAPPING[BigDecimal::class.java] = "Decimal(38,18)"
        SQL_TYPE_MAPPING[UUID::class.java] = "UUID"

        SQL_TYPE_MAPPING[Duration::class.java] = "Decimal64(9)"
        SQL_TYPE_MAPPING[kotlin.time.Duration::class.java] = "UInt64"
        SQL_TYPE_MAPPING[Date::class.java] = "UInt64"
        SQL_TYPE_MAPPING[java.sql.Date::class.java] = "UInt64"
        SQL_TYPE_MAPPING[LocalDate::class.java] = "String"
        SQL_TYPE_MAPPING[LocalDateTime::class.java] = "String"
        SQL_TYPE_MAPPING[LocalTime::class.java] = "String"
        SQL_TYPE_MAPPING[Instant::class.java] = "Decimal64(9)"
        SQL_TYPE_MAPPING[ZonedDateTime::class.java] = "String"
        SQL_TYPE_MAPPING[OffsetDateTime::class.java] = "String"
        SQL_TYPE_MAPPING[OffsetTime::class.java] = "String"
        SQL_TYPE_MAPPING[YearMonth::class.java] = "String"
        SQL_TYPE_MAPPING[MonthDay::class.java] = "String"
        SQL_TYPE_MAPPING[Period::class.java] = "String"
        SQL_TYPE_MAPPING[Year::class.java] = "UInt32"
    }

    val Class<*>.isSimple: Boolean
        get() = SQL_TYPE_MAPPING.containsKey(this) || this.isEnum

    fun Class<*>.toSqlType(): String {
        SQL_TYPE_MAPPING[this]?.let {
            return it
        }
        if (this.isEnum) {
            return "String"
        }
        throw IllegalArgumentException("Unsupported type: $this")
    }
}
