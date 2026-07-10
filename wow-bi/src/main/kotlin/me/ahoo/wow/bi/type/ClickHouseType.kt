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

internal sealed interface ClickHouseType {
    fun toSql(): kotlin.String

    sealed class Scalar(private val sql: kotlin.String) : ClickHouseType {
        final override fun toSql(): kotlin.String = sql
    }

    data object String : Scalar("String")

    data object Int8 : Scalar("Int8")

    data object Int16 : Scalar("Int16")

    data object Int32 : Scalar("Int32")

    data object Int64 : Scalar("Int64")

    data object UInt32 : Scalar("UInt32")

    data object UInt64 : Scalar("UInt64")

    data object Float32 : Scalar("Float32")

    data object Float64 : Scalar("Float64")

    data object Bool : Scalar("Bool")

    data object UUID : Scalar("UUID")

    data class Decimal(val precision: Int, val scale: Int) : Scalar("Decimal($precision,$scale)") {
        init {
            require(precision in 1..MAX_PRECISION) {
                "Decimal precision must be in 1..$MAX_PRECISION: $precision"
            }
            require(scale in 0..precision) {
                "Decimal scale must be in 0..$precision: $scale"
            }
        }

        private companion object {
            const val MAX_PRECISION: Int = 76
        }
    }

    data class Decimal64(val scale: Int) : Scalar("Decimal64($scale)") {
        init {
            require(scale in 0..MAX_SCALE) {
                "Decimal64 scale must be in 0..$MAX_SCALE: $scale"
            }
        }

        private companion object {
            const val MAX_SCALE: Int = 18
        }
    }

    data class Nullable(val type: Scalar) : ClickHouseType {
        override fun toSql(): kotlin.String = "Nullable(${type.toSql()})"
    }

    data class Array(val elementType: ClickHouseType) : ClickHouseType {
        override fun toSql(): kotlin.String = "Array(${elementType.toSql()})"
    }

    data class Map(val keyType: Scalar, val valueType: ClickHouseType) : ClickHouseType {
        override fun toSql(): kotlin.String = "Map(${keyType.toSql()}, ${valueType.toSql()})"
    }
}
