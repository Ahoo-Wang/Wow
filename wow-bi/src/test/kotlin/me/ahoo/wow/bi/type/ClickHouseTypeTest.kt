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
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class ClickHouseTypeTest {
    @Test
    fun `should render scalar types deterministically`() {
        listOf(
            ClickHouseType.String to "String",
            ClickHouseType.Int8 to "Int8",
            ClickHouseType.Int16 to "Int16",
            ClickHouseType.Int32 to "Int32",
            ClickHouseType.Int64 to "Int64",
            ClickHouseType.UInt32 to "UInt32",
            ClickHouseType.UInt64 to "UInt64",
            ClickHouseType.Float32 to "Float32",
            ClickHouseType.Float64 to "Float64",
            ClickHouseType.Bool to "Bool",
            ClickHouseType.UUID to "UUID",
            ClickHouseType.Decimal(38, 18) to "Decimal(38,18)",
            ClickHouseType.Decimal64(9) to "Decimal64(9)",
            ClickHouseType.DateTime("Asia/Shanghai") to "DateTime('Asia/Shanghai')",
            ClickHouseType.DateTime("Zone'Name") to "DateTime('Zone''Name')",
        ).forEach { (type, expectedSql) ->
            type.toSql().assert().isEqualTo(expectedSql)
        }
    }

    @Test
    fun `should render nullable and collection types structurally`() {
        ClickHouseType.Nullable(ClickHouseType.Int32).toSql()
            .assert().isEqualTo("Nullable(Int32)")
        ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.String)).toSql()
            .assert().isEqualTo("Array(Nullable(String))")
        ClickHouseType.Map(
            ClickHouseType.String,
            ClickHouseType.Nullable(ClickHouseType.Int64)
        ).toSql().assert().isEqualTo("Map(String, Nullable(Int64))")
    }

    @Test
    fun `should reject invalid decimal precision`() {
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.Decimal(0, 0)
        }.hasMessageContaining("precision")
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.Decimal(77, 0)
        }.hasMessageContaining("precision")
    }

    @Test
    fun `should reject invalid decimal scale`() {
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.Decimal(18, -1)
        }.hasMessageContaining("scale")
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.Decimal(18, 19)
        }.hasMessageContaining("scale")
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.Decimal64(-1)
        }.hasMessageContaining("scale")
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.Decimal64(19)
        }.hasMessageContaining("scale")
    }

    @Test
    fun `should reject unsafe DateTime timezone`() {
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.DateTime(" ")
        }.hasMessageContaining("timezone")
        assertThrownBy<IllegalArgumentException> {
            ClickHouseType.DateTime("Asia\nShanghai")
        }.hasMessageContaining("timezone")
    }
}
